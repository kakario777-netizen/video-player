package com.kakario.videoplayer.ai

/**
 * Whisper(음성 인식) 네이티브 함수. 폰 안에서만 동작하고 네트워크를 쓰지 않는다.
 * 라이브러리를 못 불러오면 available = false.
 */
object WhisperNative {
    val available: Boolean = runCatching { System.loadLibrary("whisper_jni") }.isSuccess

    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun transcribe(handle: Long, pcm16k: FloatArray, language: String, threads: Int, beam: Boolean): ByteArray?
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

/** llama.cpp(번역용 소형 AI 언어모델) 네이티브 함수. 폰 안에서만 동작. */
object LlamaNative {
    val available: Boolean = runCatching { System.loadLibrary("llama_jni") }.isSuccess

    @JvmStatic external fun init(modelPath: String, nCtx: Int, threads: Int): Long
    @JvmStatic external fun generate(handle: Long, system: ByteArray, user: ByteArray, prefill: ByteArray, maxTokens: Int): ByteArray?
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun free(handle: Long)
}
