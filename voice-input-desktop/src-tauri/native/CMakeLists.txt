cmake_minimum_required(VERSION 3.16)

project(voiceinput_c2_reader_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC AND VCPKG_TARGET_TRIPLET MATCHES "static")
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

option(DISABLE_TESTS "Disable libcimbar tests" ON)

set(VOICEINPUT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." CACHE PATH "VoiceInput repository root")
set(CFC_ROOT "${VOICEINPUT_ROOT}/VoiceInputApp/app/src/main/cpp" CACHE PATH "VoiceInput native cfc root")
set(LIBCIMBAR_DIR "${CFC_ROOT}/libcimbar" CACHE PATH "Path to libcimbar")

if(NOT EXISTS "${LIBCIMBAR_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "libcimbar not found: ${LIBCIMBAR_DIR}")
endif()

if(MSVC)
  add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:/permissive->" "$<$<COMPILE_LANGUAGE:CXX>:/utf-8>")
endif()

find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs photo calib3d)

include_directories(
  "${CFC_ROOT}"
  "${CFC_ROOT}/cfc-cpp"
  "${LIBCIMBAR_DIR}/src/lib"
  "${LIBCIMBAR_DIR}/src/third_party_lib"
  ${OpenCV_INCLUDE_DIRS}
)

if(NOT DEFINED OPENCV_LIBS)
  set(OPENCV_LIBS ${OpenCV_LIBS})
endif()

if(NOT DEFINED CPPFILESYSTEM)
  set(CPPFILESYSTEM "")
endif()

set(LIBCIMBAR_SUBDIRS
  src/lib/bit_file
  src/lib/chromatic_adaptation
  src/lib/cimb_translator
  src/lib/compression
  src/lib/encoder
  src/lib/extractor
  src/lib/fountain
  src/lib/image_hash
  src/lib/serialize
  src/lib/util
  src/third_party_lib/base91
  src/third_party_lib/intx
  src/third_party_lib/libcorrect
  src/third_party_lib/libpopcnt
  src/third_party_lib/wirehair
  src/third_party_lib/zstd
)

foreach(subdir ${LIBCIMBAR_SUBDIRS})
  add_subdirectory("${LIBCIMBAR_DIR}/${subdir}" "${CMAKE_CURRENT_BINARY_DIR}/libcimbar/${subdir}")
endforeach()

add_library(voiceinput_c2_reader_bridge SHARED
  c2_reader_bridge.cpp
  c2_reader_bridge.h
)

target_compile_definitions(voiceinput_c2_reader_bridge PRIVATE
  VOICEINPUT_C2_READER_BUILD
  _CRT_SECURE_NO_WARNINGS
  NOMINMAX
  WIN32_LEAN_AND_MEAN
  ZSTD_STATIC_LINKING_ONLY
)

target_link_libraries(voiceinput_c2_reader_bridge PRIVATE
  ${OpenCV_LIBS}
  cimb_translator
  extractor
  correct_static
  wirehair
  zstd
  gdi32
  user32
)
