cmake_minimum_required(VERSION 3.22)
project(audio_jni C)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_library(audio_jni SHARED audio_jni.c)
target_include_directories(audio_jni PRIVATE ${FFMPEG_DIR}/include)
target_link_libraries(audio_jni PRIVATE
    ${FFMPEG_DIR}/lib/libavformat.a
    ${FFMPEG_DIR}/lib/libavcodec.a
    ${FFMPEG_DIR}/lib/libswresample.a
    ${FFMPEG_DIR}/lib/libavutil.a
    m)
if (ANDROID)
    # 내부 FFmpeg 기호는 숨겨서 썸네일용 FFmpeg 라이브러리와 부딪히지 않게
    target_link_options(audio_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
