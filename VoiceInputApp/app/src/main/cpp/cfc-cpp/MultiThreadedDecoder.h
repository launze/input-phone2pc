#pragma once

#include "compression/zstd_decompressor.h"
#include "encoder/Decoder.h"
#include "extractor/Anchor.h"
#include "extractor/Deskewer.h"
#include "extractor/Extractor.h"
#include "extractor/Scanner.h"
#include "fountain/concurrent_fountain_decoder_sink.h"

#include "concurrent/thread_pool.h"
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <mutex>
#include <sstream>
#include <vector>

class MultiThreadedDecoder
{
public:
	MultiThreadedDecoder(std::string data_path, int mode_val);

	inline static clock_t count = 0;
	inline static clock_t bytes = 0;
	inline static clock_t perfect = 0;
	inline static clock_t decoded = 0;
	inline static uint64_t decodeMs = 0;
	inline static clock_t scanned = 0;
	inline static uint64_t scanMs = 0;
	inline static uint64_t extractMs = 0;
	inline static unsigned trackInterval = 0;
	inline static clock_t tracked = 0;
	inline static clock_t fullScans = 0;
	inline static bool diagnosticSaveRequested = false;
	inline static bool diagnosticSuccessSaved = false;
	inline static bool diagnosticFailureSaved = false;
	inline static unsigned diagnosticSuccessCount = 0;
	inline static std::mutex diagnosticMutex;
	inline static std::vector<std::string> diagnosticFiles;

	bool add(cv::Mat mat);

	void stop();
	static void request_diagnostic_save();
	static std::vector<std::string> consume_diagnostic_files();

	int mode() const;
	bool set_mode(int mode_val);
	int detected_mode() const;

	unsigned num_threads() const;
	unsigned backlog() const;
	unsigned files_in_flight() const;
	unsigned files_decoded() const;
	std::vector<std::string> get_done() const;
	std::vector<double> get_progress() const;

protected:
	int do_extract(const cv::Mat& mat, cv::Mat& img);
	void save(const cv::Mat& img);
	double max_progress() const;

	static unsigned fountain_chunk_size(int mode_val);

protected:
	int _modeVal;
	int _detectedMode;

	Decoder _dec;
	unsigned _numThreads;
	turbo::thread_pool _pool;
	concurrent_fountain_decoder_sink _writer;
	std::string _dataPath;
	unsigned _successCondition;
	std::vector<Anchor> _lastAnchors;
	unsigned _locateFrame;
	std::mutex _anchorMutex;
};

inline MultiThreadedDecoder::MultiThreadedDecoder(std::string data_path, int mode_val)
	: _modeVal(mode_val)
	, _detectedMode(0)
	, _dec(cimbar::Config::ecc_bytes(), cimbar::Config::color_bits())
	, _numThreads(std::max<unsigned>(1, std::min<unsigned>(8, std::thread::hardware_concurrency() > 1 ? std::thread::hardware_concurrency() - 1 : 1)))
	, _pool(_numThreads, 1)
	, _writer(fountain_chunk_size(mode_val), decompress_on_store<std::ofstream>(data_path, true))
	, _dataPath(data_path)
	, _successCondition(cimbar::Config::temp_conf(mode_val).capacity() * .7)
	, _locateFrame(0)
{
	FountainInit::init();
	_pool.start();
}

inline int MultiThreadedDecoder::do_extract(const cv::Mat& mat, cv::Mat& img)
{
	auto begin = std::chrono::steady_clock::now();

	std::vector<Anchor> anchors;
	bool reuseAnchors = false;
	{
		std::lock_guard<std::mutex> lock(_anchorMutex);
		reuseAnchors = trackInterval > 0 && _lastAnchors.size() >= 4 && (_locateFrame % trackInterval) != 0;
		++_locateFrame;
		if (reuseAnchors)
			anchors = _lastAnchors;
	}
	if (reuseAnchors)
	{
		++tracked;
	}
	else
	{
		Scanner scanner(mat);
		anchors = scanner.scan();
		if (anchors.size() == 3)
		{
			Scanner adaptiveScanner(mat, false);
			std::vector<Anchor> adaptiveAnchors = adaptiveScanner.scan();
			if (adaptiveAnchors.size() >= 4)
				anchors = adaptiveAnchors;
		}
		++scanned;
		++fullScans;
		scanMs += std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - begin).count();
	}

	//if (anchors.size() >= 3) save(mat);

	if (anchors.size() < 4)
	{
		bool saveFailureDiagnostic = false;
		bool progressReady = max_progress() >= 0.50;
		{
			std::lock_guard<std::mutex> lock(diagnosticMutex);
			saveFailureDiagnostic = diagnosticSaveRequested && !diagnosticFailureSaved && progressReady;
			if (saveFailureDiagnostic)
				diagnosticFailureSaved = true;
			if (diagnosticSuccessSaved && diagnosticFailureSaved)
				diagnosticSaveRequested = false;
		}
		if (saveFailureDiagnostic)
		{
			std::stringstream failName;
			failName << "diag_failed_raw_" << count << ".png";
			std::string failPath = _dataPath + "/" + failName.str();
			cv::imwrite(failPath, mat);
			std::lock_guard<std::mutex> lock(diagnosticMutex);
			diagnosticFiles.push_back(failName.str());
		}
		return Extractor::FAILURE;
	}
	if (!reuseAnchors)
	{
		std::lock_guard<std::mutex> lock(_anchorMutex);
		_lastAnchors = anchors;
	}

	begin = std::chrono::steady_clock::now();
	Corners corners(anchors);
	Deskewer de;
	img = de.deskew(mat, corners);
	extractMs += std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - begin).count();

	bool saveDiagnostic = false;
	{
		std::lock_guard<std::mutex> lock(diagnosticMutex);
		saveDiagnostic = diagnosticSaveRequested && !diagnosticSuccessSaved;
		if (saveDiagnostic)
			diagnosticSuccessSaved = true;
		if (diagnosticSaveRequested)
			++diagnosticSuccessCount;
		if (diagnosticSuccessSaved && diagnosticFailureSaved)
			diagnosticSaveRequested = false;
	}
	if (saveDiagnostic)
	{
		std::stringstream rawName;
		rawName << "diag_found_raw_" << count << ".png";
		std::stringstream deskewName;
		deskewName << "diag_found_deskew_" << count << ".png";
		std::string rawPath = _dataPath + "/" + rawName.str();
		std::string deskewPath = _dataPath + "/" + deskewName.str();
		cv::imwrite(rawPath, mat);
		cv::imwrite(deskewPath, img);
		std::lock_guard<std::mutex> lock(diagnosticMutex);
		diagnosticFiles.push_back(rawName.str());
		diagnosticFiles.push_back(deskewName.str());
	}

	return Extractor::SUCCESS;
}

inline bool MultiThreadedDecoder::add(cv::Mat mat)
{
    ++count;
    unsigned modeVal = _modeVal;
    if (modeVal == 0)
    {
        switch (count%4) {
            case 1:
                modeVal = 4;
                break;
            case 2:
                modeVal = 66;
                break;
            case 3:
                modeVal = 67;
                break;
            default:
                modeVal = 68;
        }
    }
    return _pool.try_execute( [&, mat, modeVal] () {
		cimbar::Config::update(modeVal);
		cv::Mat img;
		int res = do_extract(mat, img);
		if (res == Extractor::FAILURE)
			return;

		// if extracted image is small, we'll need to run some filters on it
		auto begin = std::chrono::steady_clock::now();
		bool should_preprocess = (res == Extractor::NEEDS_SHARPEN);
		int color_correction = modeVal==4? 1 : 2;
		unsigned decodeRes = _dec.decode_fountain(img, _writer, should_preprocess, color_correction);
		bytes += decodeRes;
		++decoded;
		decodeMs += std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - begin).count();

		if (decodeRes and _modeVal == 0)
			_detectedMode = modeVal;

		if (decodeRes >= _successCondition)
			++perfect;
	} );
}

inline void MultiThreadedDecoder::save(const cv::Mat& mat)
{
	std::stringstream fname;
	fname << _dataPath << "/scan" << (scanned-1) << ".png";
	cv::Mat bgr;
	cv::cvtColor(mat, bgr, cv::COLOR_RGB2BGR);
	cv::imwrite(fname.str(), bgr);
}

inline void MultiThreadedDecoder::stop()
{
	_pool.stop();
}

inline double MultiThreadedDecoder::max_progress() const
{
	std::vector<double> progress = _writer.get_progress();
	double maxProgress = 0.0;
	for (double p : progress)
		maxProgress = std::max(maxProgress, p);
	return maxProgress;
}

inline void MultiThreadedDecoder::request_diagnostic_save()
{
	std::lock_guard<std::mutex> lock(diagnosticMutex);
	diagnosticSaveRequested = true;
	diagnosticSuccessSaved = false;
	diagnosticFailureSaved = false;
	diagnosticSuccessCount = 0;
	diagnosticFiles.clear();
}

inline std::vector<std::string> MultiThreadedDecoder::consume_diagnostic_files()
{
	std::lock_guard<std::mutex> lock(diagnosticMutex);
	std::vector<std::string> files = diagnosticFiles;
	diagnosticFiles.clear();
	return files;
}

unsigned MultiThreadedDecoder::fountain_chunk_size(int mode_val)
{
	return cimbar::Config::temp_conf(mode_val).fountain_chunk_size();
}

inline int MultiThreadedDecoder::mode() const
{
	return _modeVal;
}

inline bool MultiThreadedDecoder::set_mode(int mode_val)
{
	if (_modeVal == mode_val)
		return true;

	if (mode_val != 0 and _writer.chunk_size() != fountain_chunk_size(mode_val))
		return false; // if so, we need to reset to change it

	// reset detectedMode iff we're switching back to autodetect
	if (mode_val == 0)
		_detectedMode = 0;

	_modeVal = mode_val;
	return true;
}

inline int MultiThreadedDecoder::detected_mode() const
{
	return _detectedMode;
}

inline unsigned MultiThreadedDecoder::num_threads() const
{
	return _numThreads;
}

inline unsigned MultiThreadedDecoder::backlog() const
{
	return _pool.queued();
}

inline unsigned MultiThreadedDecoder::files_in_flight() const
{
	return _writer.num_streams();
}

inline unsigned MultiThreadedDecoder::files_decoded() const
{
	return _writer.num_done();
}

inline std::vector<std::string> MultiThreadedDecoder::get_done() const
{
	return _writer.get_done();
}

inline std::vector<double> MultiThreadedDecoder::get_progress() const
{
	return _writer.get_progress();
}
