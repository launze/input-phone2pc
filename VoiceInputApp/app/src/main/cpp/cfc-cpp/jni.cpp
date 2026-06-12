#include "MultiThreadedDecoder.h"
#include "cimb_translator/CimbDecoder.h"
#include "cimb_translator/CimbReader.h"
#include "encoder/Decoder.h"
#include "extractor/Scanner.h"
#include "serialize/format.h"

#include <jni.h>
#include <android/log.h>
#include <opencv2/core/core.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <chrono>
#include <deque>
#include <memory>
#include <mutex>
#include <sstream>

#define TAG "CameraFileCopyCPP"

using namespace std;
using namespace cv;

namespace {
	std::shared_ptr<MultiThreadedDecoder> _proc;
	std::mutex _mutex; // for _proc
	std::set<std::string> _completed;

	unsigned _calls = 0;
	int _transferStatus = 0;
	clock_t _frameDecodeSnapshot = 0;
	clock_t _frameSuccessSnapshot = 0;
	clock_t _statsStart = clock();
	unsigned _droppedFrames = 0;
	auto _statsStartWall = std::chrono::steady_clock::now();
	std::deque<std::pair<std::chrono::steady_clock::time_point, clock_t>> _speedSamples;
	bool _payloadActive = false;
	auto _payloadStartWall = std::chrono::steady_clock::now();
	auto _payloadDoneWall = std::chrono::steady_clock::now();
	clock_t _payloadDoneBytes = 0;
	unsigned _lastFilesDecoded = 0;

	unsigned millis(unsigned num, unsigned denom)
	{
		if (!denom)
			denom = 1;
		return (num / denom) * 1000 / CLOCKS_PER_SEC;
	}

	unsigned percent(unsigned num, unsigned denom)
	{
		if (!denom)
			denom = 1;
		return (num * 100) / denom;
	}

	unsigned avg_ms(uint64_t total, unsigned denom)
	{
		if (!denom)
			denom = 1;
		return total / denom;
	}

	void drawGuidance(cv::Mat& mat, int in_progress)
	{
		int minsz = std::min(mat.cols, mat.rows);
		int guideWidth = minsz >> 7;
		int outlineWidth = guideWidth + (minsz >> 8);
		int guideLength = guideWidth << 3;
		int guideOffset = minsz >> 5;
		int outlineOffset = (outlineWidth - guideWidth) >> 1;

		cv::Scalar color = cv::Scalar(255,255,255);
		if (in_progress == 1)
			color = cv::Scalar(255,244,94); // 0,191,255
		else if (in_progress == 2)
			color = cv::Scalar(0,255,0);
		cv::Scalar outline = cv::Scalar(0,0,0);

		int xextra = 0;
		if (mat.cols > mat.rows)
			xextra = (mat.cols - mat.rows) >> 1;
		int yextra = 0;
		if (mat.rows > mat.cols)
			yextra = (mat.rows - mat.cols) >> 1;

		int lx = guideOffset + xextra;
		int ty = guideOffset + yextra;
		int outlinex = lx - outlineOffset;
		int outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, guideOffset), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, guideOffset), cv::Point(lx + guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(lx, guideOffset), cv::Point(lx, ty + guideLength), color, guideWidth);

		int rx = mat.cols - guideOffset - guideWidth - xextra;
		outlinex = rx + outlineOffset;
		outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(rx, outliney), cv::Point(rx - guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, guideOffset), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(rx, guideOffset), cv::Point(rx - guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(rx, guideOffset), cv::Point(rx, ty + guideLength), color, guideWidth);

		int by = mat.rows - guideOffset - guideWidth - yextra;
		outlinex = lx - outlineOffset;
		outliney = by + outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, by), cv::Point(outlinex, by - guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx + guideLength, by), color, guideWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx, by - guideLength), color, guideWidth);
	}

	void drawProgress(cv::Mat& mat, const std::vector<double>& progress)
	{
		if (progress.empty())
			return;

		int minsz = std::min(mat.cols, mat.rows);
		int fillWidth = minsz >> 7;
		int outlineWidth = fillWidth + (minsz >> 8) + 1;

		int barLength = (minsz >> 1) + (minsz >> 2);
		int barOffsetW = (minsz - barLength) >> 3;
		int barOffsetL = (minsz - barLength) >> 1;
		int outlineOffset = (outlineWidth - fillWidth) >> 1;

		cv::Scalar color = cv::Scalar(255,255,255);
		cv::Scalar outline = cv::Scalar(0,0,0);

		int px = barOffsetW;
		int py = mat.rows - barOffsetL;
		for (double p : progress)
		{
			int fillLength = (barLength * p);
			cv::line(mat, cv::Point(px - outlineOffset, py), cv::Point(px - outlineOffset, py - barLength), outline, outlineWidth);
			cv::line(mat, cv::Point(px, py), cv::Point(px, py - fillLength), color, fillWidth);

			px += outlineWidth + outlineWidth;
		}
	}

	void drawDebugInfo(cv::Mat& mat, MultiThreadedDecoder& proc)
	{
		std::stringstream sstop;
		sstop << "cfc using " << proc.num_threads() << " thread(s). " << proc.mode() << ":" << proc.detected_mode() << "..." << proc.backlog() << "? ";
		sstop << (MultiThreadedDecoder::bytes / std::max<double>(1, MultiThreadedDecoder::decoded)) << "b v0.6.4";
		std::stringstream ssmid;
		ssmid << "#: " << MultiThreadedDecoder::perfect << " / " << MultiThreadedDecoder::decoded << " / " << MultiThreadedDecoder::scanned << " / " << _calls;
		std::stringstream ssperf;
		ssperf << "scan: " << avg_ms(MultiThreadedDecoder::scanMs, MultiThreadedDecoder::scanned);
		ssperf << ", extract: " << avg_ms(MultiThreadedDecoder::extractMs, MultiThreadedDecoder::decoded);
		ssperf << ", decode: " << avg_ms(MultiThreadedDecoder::decodeMs, MultiThreadedDecoder::decoded);
		std::stringstream sstats;
		sstats << "Files received: " << proc.files_decoded() << ", in flight: " << proc.files_in_flight() << ". ";
		sstats << percent(MultiThreadedDecoder::perfect, MultiThreadedDecoder::decoded) << "% decode. ";
		sstats << percent(MultiThreadedDecoder::decoded, MultiThreadedDecoder::scanned) << "% scan.";

		cv::putText(mat, sstop.str(), cv::Point(5,50), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, ssmid.str(), cv::Point(5,100), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, ssperf.str(), cv::Point(5,150), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, sstats.str(), cv::Point(5,200), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);

		/*std::stringstream ssperf2;
		ssperf2 << "reader ctor: " << millis(Decoder::readerInitTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", fount: " << millis(Decoder::fountTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", dodecode: " << millis(Decoder::decodeTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", readloop: " << millis(Decoder::bbTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", rss: " << millis(Decoder::rssTicks, MultiThreadedDecoder::decoded);
		cv::putText(mat, ssperf2.str(), cv::Point(5,300), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		//*/
	}

	std::string jstring_to_cppstr(JNIEnv *env, const jstring& dataPathObj)
	{
		const char* temp = env->GetStringUTFChars(dataPathObj, NULL);
		string res(temp);
		env->ReleaseStringUTFChars(dataPathObj, temp);
		return res;
	}

	cv::Mat centered_compact_view(const cv::Mat& mat)
	{
		int minDim = std::min(mat.cols, mat.rows);
		int maxDim = std::max(mat.cols, mat.rows);
		if (minDim < 900 || maxDim <= minDim * 7 / 6)
			return mat;

		if (mat.cols > mat.rows)
		{
			int width = minDim * 7 / 6;
			int x = (mat.cols - width) / 2;
			return mat(cv::Rect(x, 0, width, mat.rows));
		}

		int height = minDim * 7 / 6;
		int y = (mat.rows - height) / 2;
		return mat(cv::Rect(0, y, mat.cols, height));
	}

	void reset_state_locked()
	{
		if (_proc)
			_proc->stop();
		_proc = nullptr;
		_completed.clear();
		_calls = 0;
		_transferStatus = 0;
		_frameDecodeSnapshot = 0;
		_frameSuccessSnapshot = 0;
		_statsStart = clock();
		_droppedFrames = 0;
		_statsStartWall = std::chrono::steady_clock::now();
		_speedSamples.clear();
		_payloadActive = false;
		_payloadStartWall = _statsStartWall;
		_payloadDoneWall = _statsStartWall;
		_payloadDoneBytes = 0;
		_lastFilesDecoded = 0;
		MultiThreadedDecoder::count = 0;
		MultiThreadedDecoder::bytes = 0;
		MultiThreadedDecoder::perfect = 0;
		MultiThreadedDecoder::decoded = 0;
		MultiThreadedDecoder::decodeMs = 0;
		MultiThreadedDecoder::scanned = 0;
		MultiThreadedDecoder::scanMs = 0;
		MultiThreadedDecoder::extractMs = 0;
		MultiThreadedDecoder::tracked = 0;
		MultiThreadedDecoder::fullScans = 0;
		MultiThreadedDecoder::diagnosticSaveRequested = false;
		MultiThreadedDecoder::diagnosticSuccessSaved = false;
		MultiThreadedDecoder::diagnosticFailureSaved = false;
		MultiThreadedDecoder::diagnosticSuccessCount = 0;
		MultiThreadedDecoder::diagnosticFiles.clear();
		Decoder::readerInitMs = 0;
		Decoder::symbolReadMs = 0;
		Decoder::symbolFlushMs = 0;
		Decoder::ccmMs = 0;
		Decoder::colorReadMs = 0;
		Decoder::colorFlushMs = 0;
	}
}

extern "C" {
jstring JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_processImageJNI(JNIEnv *env, jobject instance, jlong matAddr, jstring dataPathObj, jint modeInt)
{
	++_calls;

	// get params from raw address
	Mat &mat = *(Mat *) matAddr;
	string dataPath = jstring_to_cppstr(env, dataPathObj);
	int modeVal = (int)modeInt;

	std::shared_ptr<MultiThreadedDecoder> proc;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if (!_proc or !_proc->set_mode(modeVal))
			_proc = std::make_shared<MultiThreadedDecoder>(dataPath, modeVal);
		proc = _proc;
	}

	clock_t begin = clock();
	if (proc->backlog() >= 1)
		++_droppedFrames;
	else
	{
		cv::Mat img = centered_compact_view(mat).clone();
		if (!proc->add(img))
			++_droppedFrames;
	}

	if (_calls & 31)
	{
		clock_t decodeSnapshot = proc->decoded;
		clock_t perfectSnapshot = proc->perfect;
		_transferStatus = perfectSnapshot > _frameSuccessSnapshot; // a bit silly, but 1 == partial decode
		_transferStatus += (decodeSnapshot > _frameDecodeSnapshot); // 2 == full decode
		_frameDecodeSnapshot = decodeSnapshot;
		_frameSuccessSnapshot = perfectSnapshot;
	}

	drawGuidance(mat, _transferStatus);
	//drawDebugInfo(mat, *proc);

	// log computation time to Android Logcat
	double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
	__android_log_print(ANDROID_LOG_INFO, TAG, "processImage computation time = %f seconds\n",
						totalTime);

	// return a decoded file to prompt the user to save it, if there is a new one
	string result;
	if (proc->detected_mode()) // repurpose str for special message passing
		result = fmt::format("/{}", proc->detected_mode());

	std::vector<string> all_decodes = proc->get_done();
	for (string& s : all_decodes)
		if (_completed.find(s) == _completed.end())
		{
			_completed.insert(s);
			result = s;
	}
	return env->NewStringUTF(result.c_str());
}

jstring JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_consumeDiagnosticFilesJNI(JNIEnv *env, jobject instance)
{
	std::vector<std::string> files = MultiThreadedDecoder::consume_diagnostic_files();
	std::stringstream ss;
	for (size_t i = 0; i < files.size(); ++i)
	{
		if (i)
			ss << "\n";
		ss << files[i];
	}
	return env->NewStringUTF(ss.str().c_str());
}

jstring JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_getStatsJNI(JNIEnv *env, jobject instance)
{
	std::shared_ptr<MultiThreadedDecoder> proc;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		proc = _proc;
	}

	auto now = std::chrono::steady_clock::now();
	double elapsed = std::max<double>(1.0, std::chrono::duration<double>(now - _statsStartWall).count());
	double cameraFps = _calls / elapsed;
	clock_t payloadBytes = MultiThreadedDecoder::bytes;
	if (!_payloadActive && payloadBytes > 0)
	{
		_payloadActive = true;
		_payloadStartWall = now;
		_payloadDoneWall = now;
		_payloadDoneBytes = 0;
		_speedSamples.clear();
	}

	unsigned filesDecoded = proc ? proc->files_decoded() : 0;
	if (_payloadActive && filesDecoded > _lastFilesDecoded)
	{
		_payloadDoneWall = now;
		_payloadDoneBytes = payloadBytes;
		_lastFilesDecoded = filesDecoded;
	}

	if (_payloadActive)
		_speedSamples.emplace_back(now, payloadBytes);
	while (_speedSamples.size() > 1 &&
		   std::chrono::duration<double>(now - _speedSamples.front().first).count() > 5.0)
		_speedSamples.pop_front();
	double windowSpeedKb = 0.0;
	if (!_speedSamples.empty())
	{
		double windowSeconds = std::max<double>(0.001, std::chrono::duration<double>(now - _speedSamples.front().first).count());
		windowSpeedKb = ((payloadBytes - _speedSamples.front().second) / 1024.0) / windowSeconds;
	}
	auto avgEndWall = _payloadDoneBytes > 0 ? _payloadDoneWall : now;
	clock_t avgBytes = _payloadDoneBytes > 0 ? _payloadDoneBytes : payloadBytes;
	double activeSeconds = _payloadActive ? std::max<double>(1.0, std::chrono::duration<double>(avgEndWall - _payloadStartWall).count()) : 1.0;
	double activeSpeedKb = _payloadActive ? (avgBytes / 1024.0) / activeSeconds : 0.0;
	unsigned locateFrames = MultiThreadedDecoder::scanned + MultiThreadedDecoder::tracked;
	unsigned scanOk = percent(MultiThreadedDecoder::decoded, locateFrames);
	unsigned decodeOk = percent(MultiThreadedDecoder::perfect, MultiThreadedDecoder::decoded);
	unsigned progressPct = 0;
	if (proc)
	{
		std::vector<double> progress = proc->get_progress();
		double maxProgress = 0.0;
		for (double p : progress)
			maxProgress = std::max(maxProgress, p);
		progressPct = std::min<unsigned>(100, maxProgress * 100);
	}

	std::stringstream ss;
	ss.setf(std::ios::fixed);
	ss.precision(2);
	ss << "Progress: " << progressPct << "%\n";
	ss << "Camera FPS: " << cameraFps << "\n";
	ss << "Payload speed: " << windowSpeedKb << " KB/s 5s, " << activeSpeedKb << " active avg\n";
	ss << "Frames: " << _calls
	   << "  scanned: " << MultiThreadedDecoder::scanned
	   << "  decoded: " << MultiThreadedDecoder::decoded << "\n";
	ss << "Scan OK: " << scanOk << "%  Decode OK: " << decodeOk << "%\n";
	ss << "Locate: reuse " << MultiThreadedDecoder::tracked
	   << " / full " << MultiThreadedDecoder::fullScans
	   << "  interval " << MultiThreadedDecoder::trackInterval << "\n";
	ss << "Mode: ";
	if (proc)
		ss << proc->mode() << " / detected " << proc->detected_mode()
		   << "  backlog " << proc->backlog() << "  drop " << _droppedFrames << "\n"
		   << "Work ms: scan " << avg_ms(MultiThreadedDecoder::scanMs, MultiThreadedDecoder::scanned)
		   << "  extract " << avg_ms(MultiThreadedDecoder::extractMs, MultiThreadedDecoder::decoded)
		   << "  decode " << avg_ms(MultiThreadedDecoder::decodeMs, MultiThreadedDecoder::decoded) << "\n"
		   << "Dec ms: init " << avg_ms(Decoder::readerInitMs, MultiThreadedDecoder::decoded)
		   << "  sym " << avg_ms(Decoder::symbolReadMs, MultiThreadedDecoder::decoded)
		   << "/" << avg_ms(Decoder::symbolFlushMs, MultiThreadedDecoder::decoded)
		   << "  ccm " << avg_ms(Decoder::ccmMs, MultiThreadedDecoder::decoded)
		   << "  col " << avg_ms(Decoder::colorReadMs, MultiThreadedDecoder::decoded)
		   << "/" << avg_ms(Decoder::colorFlushMs, MultiThreadedDecoder::decoded) << "\n"
		   << "Threads: " << proc->num_threads() << "\n"
		   << "Files: " << proc->files_decoded() << " done, " << proc->files_in_flight() << " active";
	else
		ss << "waiting";

	return env->NewStringUTF(ss.str().c_str());
}

void JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_resetStatsJNI(JNIEnv *env, jobject instance) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Reset cfc-cpp stats\n");

	std::lock_guard<std::mutex> lock(_mutex);
	reset_state_locked();
}

void JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_shutdownJNI(JNIEnv *env, jobject instance) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Shutdown cfc-cpp\n");

	std::lock_guard<std::mutex> lock(_mutex);
	reset_state_locked();
}

void JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_setTrackingJNI(JNIEnv *env, jobject instance, jint interval) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Set tracking interval = %d\n", interval);

	std::lock_guard<std::mutex> lock(_mutex);
	MultiThreadedDecoder::trackInterval = interval > 0 ? (unsigned)interval : 0;
}

void JNICALL
Java_com_voiceinput_cimbar_FileTransferScanActivity_requestDiagnosticSaveJNI(JNIEnv *env, jobject instance) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Request diagnostic save on next successful locate\n");
	MultiThreadedDecoder::request_diagnostic_save();
}

}
