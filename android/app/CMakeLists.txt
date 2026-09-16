
cmake_minimum_required(VERSION 3.2)

set(CMAKE_CXX_STANDARD 14)

add_library(chiaki-jni SHARED
        src/main/cpp/chiaki-jni.c
        src/main/cpp/log.h
        src/main/cpp/log.c
        src/main/cpp/video-decoder.h
        src/main/cpp/video-decoder.c
        src/main/cpp/video-presenter.h
        src/main/cpp/video-presenter-age.h
        src/main/cpp/video-presenter-age.c
        src/main/cpp/video-presenter-recovery.h
        src/main/cpp/video-presenter-recovery.c
        src/main/cpp/video-presenter.c
        src/main/cpp/audio-decoder.h
        src/main/cpp/audio-decoder.c
        src/main/cpp/audio-output.h
        src/main/cpp/audio-output.cpp
        src/main/cpp/circular-fifo.hpp)

if(CHIAKI_ANDROID_EGL_RENDERER)
    target_sources(chiaki-jni PRIVATE
            src/main/cpp/egl-renderer.cpp
            src/main/cpp/egl-renderer.h)
    target_compile_definitions(chiaki-jni PRIVATE CHIAKI_ANDROID_EGL_RENDERER=1)
endif()
target_link_libraries(chiaki-jni chiaki-lib)

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_MEDIANDK mediandk)
find_library(ANDROID_LIB_ANDROID android)
if(CHIAKI_ANDROID_EGL_RENDERER)
    find_library(ANDROID_LIB_EGL EGL)
    find_library(ANDROID_LIB_GLESV3 GLESv3)
    target_link_libraries(chiaki-jni "${ANDROID_LIB_EGL}" "${ANDROID_LIB_GLESV3}")
endif()
target_link_libraries(chiaki-jni "${ANDROID_LIB_LOG}" "${ANDROID_LIB_MEDIANDK}" "${ANDROID_LIB_ANDROID}")

find_package (oboe REQUIRED CONFIG)
target_link_libraries(chiaki-jni oboe::oboe)
