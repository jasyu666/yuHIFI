cmake_minimum_required(VERSION 3.22.1)
project(hifiprobe C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
#  libusb 1.0.27 —— 静态库
#
#  注意：libusb 1.0.27 的发行包不提供 CMakeLists.txt（CMake 支持是更晚
#  版本才加入的），因此这里按其官方 android/jni/libusb.mk 给出的权威
#  源文件清单手工构建，编译宏沿用 android/config.h。
# ============================================================
get_filename_component(LIBUSB_ROOT
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb-1.0.27" ABSOLUTE)

if(NOT EXISTS "${LIBUSB_ROOT}/libusb/core.c")
    message(FATAL_ERROR "找不到 libusb 源码，期望路径: ${LIBUSB_ROOT}")
endif()

message(STATUS "LIBUSB_ROOT = ${LIBUSB_ROOT}")

add_library(usb1.0 STATIC
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
        )

target_include_directories(usb1.0 PRIVATE
        ${LIBUSB_ROOT}/android          # 提供 <config.h> 的 Android 版本
        ${LIBUSB_ROOT}/libusb
        ${LIBUSB_ROOT}/libusb/os
        )

target_compile_options(usb1.0 PRIVATE
        -fvisibility=hidden
        -pthread
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-unused-variable
        )

target_link_libraries(usb1.0 PUBLIC log)

# ============================================================
#  FFmpeg —— 预编译的 arm64-v8a 动态库
#
#  由 third_party/_ff/ 下的脚本用 NDK + zig 交叉编译得到。
#  之所以用动态库而非静态：FFmpeg 是 LGPL，动态链接才不必提供
#  可重链接的目标文件，合规上最省事。
#
#  configure 对 target_os=android 会自动 disable symver，
#  所以 soname 不带版本号（就是 libavcodec.so），可直接进 jniLibs。
# ============================================================
get_filename_component(FFMPEG_ROOT
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/_ff/out/arm64-v8a" ABSOLUTE)

if(NOT EXISTS "${FFMPEG_ROOT}/lib/libavcodec.so")
    message(FATAL_ERROR "找不到 FFmpeg，期望路径: ${FFMPEG_ROOT}")
endif()
message(STATUS "FFMPEG_ROOT = ${FFMPEG_ROOT}")

# 运行期由 jniLibs 提供这些 .so，这里只是让链接器能找到
foreach(fflib avcodec avformat avutil swresample)
    add_library(${fflib} SHARED IMPORTED)
    set_target_properties(${fflib} PROPERTIES
            IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/lib${fflib}.so")
endforeach()

add_library(ffmpeg INTERFACE)
target_include_directories(ffmpeg INTERFACE "${FFMPEG_ROOT}/include")
target_link_libraries(ffmpeg INTERFACE avcodec avformat avutil swresample)

# ============================================================
#  JNI 桥 + isochronous 播放引擎
# ============================================================
add_library(hifiprobe SHARED
        native_bridge.cpp
        iso_player.cpp
        system_audio_sink.cpp
        ffmpeg_decoder.cpp
        dsd_reader.cpp
        media_probe.cpp
        resampler.cpp
        tone_source.cpp
        audio_engine.cpp
        )

target_include_directories(hifiprobe PRIVATE
        ${LIBUSB_ROOT}/libusb
        ${CMAKE_CURRENT_SOURCE_DIR}
        )

target_compile_options(hifiprobe PRIVATE
        -Wall
        -Wno-unused-parameter
        )

find_library(log-lib log)
find_library(android-lib android)
# AAudio（API 26+）—— 系统音频那条非 bit-perfect 的通路用它
find_library(aaudio-lib aaudio)

target_link_libraries(hifiprobe
        usb1.0
        ffmpeg
        ${log-lib}
        ${android-lib}
        ${aaudio-lib}
        )
