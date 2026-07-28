#pragma once

#include <stddef.h>

#ifdef _WIN32
#ifdef VOICEINPUT_C2_READER_BUILD
#define VOICEINPUT_C2_API __declspec(dllexport)
#else
#define VOICEINPUT_C2_API __declspec(dllimport)
#endif
#else
#define VOICEINPUT_C2_API
#endif

extern "C" {

VOICEINPUT_C2_API int voiceinput_c2_reader_start(
    const char* output_dir,
    int mode,
    int interval_ms,
    int capture_left,
    int capture_top,
    int capture_width,
    int capture_height,
    char* error_buffer,
    size_t error_buffer_len);

VOICEINPUT_C2_API void voiceinput_c2_reader_stop();

VOICEINPUT_C2_API int voiceinput_c2_reader_is_running();

VOICEINPUT_C2_API int voiceinput_c2_reader_status(char* status_buffer, size_t status_buffer_len);

}
