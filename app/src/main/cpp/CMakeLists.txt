# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("chapter07")

# 设置FFmpeg头文件目录（相对于这个CMakeLists.txt的位置）
set(FFMPEG_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg_include)
set(OPENCV_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opencv_include/include)
# 设置FFmpeg库文件目录（指向jniLibs中对应架构的目录）
# ${ANDROID_ABI} 会自动获取当前编译的架构，如 arm64-v8a
set(FFMPEG_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(OPENCV_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)

# RTSP播放器相关
set(RTSP_PLAYER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rtsp_player)
set(RTSP_PLAYER_SOURCES
    ${RTSP_PLAYER_DIR}/rtsp_player_jni.cpp
    ${RTSP_PLAYER_DIR}/rtsp_stream_player.cpp
)

# 添加FFmpeg头文件搜索路径
include_directories(${FFMPEG_INCLUDE_DIR})
include_directories(${OPENCV_INCLUDE_DIR})

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    native-lib.cpp
    ${RTSP_PLAYER_SOURCES})

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # FFmpeg动态库（按依赖顺序链接）
    ${FFMPEG_LIB_DIR}/libavformat.so
    ${FFMPEG_LIB_DIR}/libavcodec.so
    ${FFMPEG_LIB_DIR}/libavutil.so
    ${FFMPEG_LIB_DIR}/libavdevice.so
    ${FFMPEG_LIB_DIR}/libavfilter.so
    ${FFMPEG_LIB_DIR}/libswscale.so
    ${FFMPEG_LIB_DIR}/libswresample.so
    # OpenCV库
    ${OPENCV_LIB_DIR}/${ANDROID_ABI}/libopencv_java4.so
    # Android系统库
    jnigraphics
    android
    GLESv2
    EGL
    log)