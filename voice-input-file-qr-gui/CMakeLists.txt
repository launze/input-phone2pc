cmake_minimum_required(VERSION 3.16)

project(voiceinput_file_qr_gui LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC AND VCPKG_TARGET_TRIPLET MATCHES "static")
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

option(DISABLE_TESTS "Disable libcimbar tests" ON)

set(LIBCIMBAR_DIR "${CMAKE_CURRENT_LIST_DIR}/../VoiceInputApp/app/src/main/cpp/libcimbar" CACHE PATH "Path to libcimbar")

if(NOT EXISTS "${LIBCIMBAR_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "libcimbar not found: ${LIBCIMBAR_DIR}")
endif()

if(MSVC)
  add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:/permissive->" "$<$<COMPILE_LANGUAGE:CXX>:/utf-8>")
endif()

find_package(wxWidgets REQUIRED COMPONENTS core base)
include(${wxWidgets_USE_FILE})

find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs photo)

include_directories(
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

add_executable(voiceinput-file-qr-gui WIN32
  src/main.cpp
  src/qr_encoder_backend.cpp
)

target_include_directories(voiceinput-file-qr-gui PRIVATE
  "${LIBCIMBAR_DIR}/src/lib"
  "${LIBCIMBAR_DIR}/src/third_party_lib"
)

target_link_libraries(voiceinput-file-qr-gui PRIVATE
  ${wxWidgets_LIBRARIES}
  ${OpenCV_LIBS}
  bit_file
  base91
  chromatic_adaptation
  cimb_translator
  compression
  encoder
  fountain
  image_hash
  intx
  libpopcnt
  serialize
  util
  correct_static
  wirehair
  zstd
)

if(WIN32)
  target_compile_definitions(voiceinput-file-qr-gui PRIVATE
    _CRT_SECURE_NO_WARNINGS
    NOMINMAX
    WIN32_LEAN_AND_MEAN
  )
endif()
