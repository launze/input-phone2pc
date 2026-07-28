#define VOICEINPUT_C2_READER_BUILD

#include "c2_reader_bridge.h"

#include "MultiThreadedDecoder.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

struct ReaderState {
    std::atomic_bool running{false};
    std::thread worker;
    std::mutex mutex;
    std::string output_dir;
    std::string last_error;
    std::string last_saved;
    unsigned frames = 0;
    int progress = 0;
    unsigned backlog = 0;
    unsigned files_decoded = 0;
    unsigned scanned = 0;
    unsigned decoded = 0;
    unsigned perfect = 0;
    unsigned tracked = 0;
    int mode = 0;
    int detected_mode = 0;
    RECT capture_rect_value{0, 0, 0, 0};
    std::vector<std::string> diagnostics;
};

ReaderState g_state;

RECT capture_rect() {
    RECT rect;
    rect.left = GetSystemMetrics(SM_XVIRTUALSCREEN);
    rect.top = GetSystemMetrics(SM_YVIRTUALSCREEN);
    rect.right = rect.left + GetSystemMetrics(SM_CXVIRTUALSCREEN);
    rect.bottom = rect.top + GetSystemMetrics(SM_CYVIRTUALSCREEN);
    return rect;
}

std::string json_escape(const std::string& value) {
    std::string result;
    result.reserve(value.size() + 8);
    for (char ch : value) {
        switch (ch) {
        case '\\':
            result += "\\\\";
            break;
        case '"':
            result += "\\\"";
            break;
        case '\n':
            result += "\\n";
            break;
        case '\r':
            result += "\\r";
            break;
        case '\t':
            result += "\\t";
            break;
        default:
            result += ch;
            break;
        }
    }
    return result;
}

cv::Mat capture_screen_mat(const RECT& rect) {
    const int width = rect.right - rect.left;
    const int height = rect.bottom - rect.top;
    if (width <= 0 || height <= 0) {
        return cv::Mat();
    }

    HDC screen_dc = GetDC(nullptr);
    HDC memory_dc = CreateCompatibleDC(screen_dc);

    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = width;
    bmi.bmiHeader.biHeight = -height;
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HBITMAP bitmap = CreateDIBSection(screen_dc, &bmi, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!bitmap || !bits) {
        if (bitmap) {
            DeleteObject(bitmap);
        }
        DeleteDC(memory_dc);
        ReleaseDC(nullptr, screen_dc);
        return cv::Mat();
    }

    HGDIOBJ old_bitmap = SelectObject(memory_dc, bitmap);
    BOOL copied = BitBlt(memory_dc, 0, 0, width, height, screen_dc, rect.left, rect.top, SRCCOPY | CAPTUREBLT);

    cv::Mat rgb;
    if (copied) {
        cv::Mat bgra(height, width, CV_8UC4, bits);
        cv::cvtColor(bgra, rgb, cv::COLOR_BGRA2RGB);
    }

    SelectObject(memory_dc, old_bitmap);
    DeleteObject(bitmap);
    DeleteDC(memory_dc);
    ReleaseDC(nullptr, screen_dc);
    return rgb;
}

int progress_percent(const std::vector<double>& progress) {
    double best = 0.0;
    for (double item : progress) {
        best = std::max(best, item);
    }
    best = std::max(0.0, std::min(1.0, best));
    return static_cast<int>((best * 100.0) + 0.5);
}

void copy_message(const std::string& message, char* buffer, size_t buffer_len) {
    if (!buffer || buffer_len == 0) {
        return;
    }
    const size_t count = std::min(buffer_len - 1, message.size());
    std::copy(message.data(), message.data() + count, buffer);
    buffer[count] = '\0';
}

void set_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.last_error = message;
}

void reader_loop(std::string output_dir, int mode, int interval_ms, RECT rect) {
    try {
        std::filesystem::create_directories(output_dir);
        MultiThreadedDecoder::trackInterval = 8;
        MultiThreadedDecoder decoder(output_dir, mode);
        std::set<std::string> completed;

        while (g_state.running.load()) {
            cv::Mat frame = capture_screen_mat(rect);
            if (!frame.empty()) {
                decoder.add(frame);
                std::lock_guard<std::mutex> lock(g_state.mutex);
                ++g_state.frames;
            }

            std::string last_saved;
            for (const std::string& done : decoder.get_done()) {
                if (completed.insert(done).second) {
                    last_saved = done;
                }
            }

            bool decoded_file = false;
            {
                std::lock_guard<std::mutex> lock(g_state.mutex);
                g_state.progress = progress_percent(decoder.get_progress());
                g_state.backlog = decoder.backlog();
                g_state.files_decoded = decoder.files_decoded();
                g_state.scanned = static_cast<unsigned>(MultiThreadedDecoder::scanned);
                g_state.decoded = static_cast<unsigned>(MultiThreadedDecoder::decoded);
                g_state.perfect = static_cast<unsigned>(MultiThreadedDecoder::perfect);
                g_state.tracked = static_cast<unsigned>(MultiThreadedDecoder::tracked);
                g_state.mode = decoder.mode();
                g_state.detected_mode = decoder.detected_mode();
                if (!last_saved.empty()) {
                    g_state.last_saved = last_saved;
                }
                decoded_file = g_state.files_decoded > 0 || !g_state.last_saved.empty();
            }

            if (decoded_file) {
                OutputDebugStringA("decoded file complete, auto stopping\n");
                g_state.running.store(false);
                break;
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(std::max(1, interval_ms)));
        }

        decoder.stop();
        std::string final_saved;
        for (const std::string& done : decoder.get_done()) {
            if (completed.insert(done).second) {
                final_saved = done;
            }
        }
        {
            std::lock_guard<std::mutex> lock(g_state.mutex);
            g_state.progress = progress_percent(decoder.get_progress());
            g_state.backlog = decoder.backlog();
            g_state.files_decoded = decoder.files_decoded();
            g_state.scanned = static_cast<unsigned>(MultiThreadedDecoder::scanned);
            g_state.decoded = static_cast<unsigned>(MultiThreadedDecoder::decoded);
            g_state.perfect = static_cast<unsigned>(MultiThreadedDecoder::perfect);
            g_state.tracked = static_cast<unsigned>(MultiThreadedDecoder::tracked);
            g_state.mode = decoder.mode();
            g_state.detected_mode = decoder.detected_mode();
            if (!final_saved.empty()) {
                g_state.last_saved = final_saved;
            }
            if (g_state.files_decoded > 0 || !g_state.last_saved.empty()) {
                g_state.progress = 100;
            }
        }
    } catch (const std::exception& e) {
        set_error(e.what());
    } catch (...) {
        set_error("二维码读取器发生未知错误");
    }

    g_state.running.store(false);
}

} // namespace

int voiceinput_c2_reader_start(
    const char* output_dir,
    int mode,
    int interval_ms,
    int capture_left,
    int capture_top,
    int capture_width,
    int capture_height,
    char* error_buffer,
    size_t error_buffer_len) {
    if (!output_dir || !*output_dir) {
        copy_message("输出目录不能为空", error_buffer, error_buffer_len);
        return 0;
    }

    bool expected = false;
    if (!g_state.running.compare_exchange_strong(expected, true)) {
        return 1;
    }

    if (g_state.worker.joinable()) {
        g_state.worker.join();
    }

    RECT rect;
    if (capture_width > 0 && capture_height > 0) {
        rect.left = capture_left;
        rect.top = capture_top;
        rect.right = capture_left + capture_width;
        rect.bottom = capture_top + capture_height;
    } else {
        rect = capture_rect();
    }

    {
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.output_dir = output_dir;
        g_state.last_error.clear();
        g_state.last_saved.clear();
        g_state.frames = 0;
        g_state.progress = 0;
        g_state.backlog = 0;
        g_state.files_decoded = 0;
        g_state.scanned = 0;
        g_state.decoded = 0;
        g_state.perfect = 0;
        g_state.tracked = 0;
        g_state.mode = mode;
        g_state.detected_mode = 0;
        g_state.capture_rect_value = rect;
        g_state.diagnostics.clear();
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
        MultiThreadedDecoder::trackInterval = 8;
        MultiThreadedDecoder::diagnosticSaveRequested = false;
        MultiThreadedDecoder::diagnosticSuccessSaved = false;
        MultiThreadedDecoder::diagnosticFailureSaved = false;
        MultiThreadedDecoder::diagnosticSuccessCount = 0;
        MultiThreadedDecoder::diagnosticFiles.clear();
    }

    std::ostringstream start_log;
    start_log << "start output=" << output_dir
              << " mode=" << mode
              << " rect=" << rect.left << "," << rect.top
              << " " << (rect.right - rect.left) << "x" << (rect.bottom - rect.top)
              << " interval=" << interval_ms << "ms";
    OutputDebugStringA((start_log.str() + "\n").c_str());

    try {
        g_state.worker = std::thread(reader_loop, std::string(output_dir), mode, interval_ms, rect);
    } catch (const std::exception& e) {
        g_state.running.store(false);
        copy_message(e.what(), error_buffer, error_buffer_len);
        return 0;
    }

    return 1;
}

void voiceinput_c2_reader_stop() {
    g_state.running.store(false);
    if (g_state.worker.joinable()) {
        g_state.worker.join();
    }
}

int voiceinput_c2_reader_is_running() {
    return g_state.running.load() ? 1 : 0;
}

int voiceinput_c2_reader_status(char* status_buffer, size_t status_buffer_len) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    std::ostringstream json;
    json << "{"
         << "\"running\":" << (g_state.running.load() ? "true" : "false") << ","
         << "\"frames\":" << g_state.frames << ","
         << "\"progress\":" << g_state.progress << ","
         << "\"backlog\":" << g_state.backlog << ","
         << "\"files_decoded\":" << g_state.files_decoded << ","
         << "\"scanned\":" << g_state.scanned << ","
         << "\"decoded\":" << g_state.decoded << ","
         << "\"perfect\":" << g_state.perfect << ","
         << "\"tracked\":" << g_state.tracked << ","
         << "\"mode\":" << g_state.mode << ","
         << "\"detected_mode\":" << g_state.detected_mode << ","
         << "\"capture_left\":" << g_state.capture_rect_value.left << ","
         << "\"capture_top\":" << g_state.capture_rect_value.top << ","
         << "\"capture_width\":" << (g_state.capture_rect_value.right - g_state.capture_rect_value.left) << ","
         << "\"capture_height\":" << (g_state.capture_rect_value.bottom - g_state.capture_rect_value.top) << ","
         << "\"diagnostics\":[";
    for (size_t i = 0; i < g_state.diagnostics.size(); ++i) {
        if (i) {
            json << ",";
        }
        json << "\"" << json_escape(g_state.diagnostics[i]) << "\"";
    }
    json << "],"
         << "\"output_dir\":\"" << json_escape(g_state.output_dir) << "\","
         << "\"last_saved\":\"" << json_escape(g_state.last_saved) << "\","
         << "\"error\":\"" << json_escape(g_state.last_error) << "\""
         << "}";
    copy_message(json.str(), status_buffer, status_buffer_len);
    return 1;
}
