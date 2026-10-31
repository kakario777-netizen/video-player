package com.kakario.videoplayer.ai

import java.io.File

/**
 * AI 엔진 라이브러리 불러오기.
 * 폰 CPU가 AI 계산 명령어(i8mm)를 지원하고 '폰 성능 기능 더 쓰기'가 켜져 있으면 고성능판을,
 * 아니면 기본판을 쓴다. 앱 시작 때(MainActivity) preferFast를 설정에서 읽어 넣는다.
 */
object AiLibs {
    /** 설정의 '폰 성능 기능 더 쓰기' (기본 켜짐 — 지원하는 폰이면 자동으로 고성능판). */
    @Volatile var preferFast: Boolean = true

    /** 이 폰 CPU가 i8mm(AI 행렬 계산 명령어)를 지원하는지. */
    val hasI8mm: Boolean by lazy {
        runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features") }
                .let { lines -> lines.isNotEmpty() && lines.all { Regex("""\bi8mm\b""").containsMatchIn(it) } }
        }.getOrDefault(false)
    }

    /** 실제로 고성능판이 불러와졌는지 (설정 화면 표시용). */
    @Volatile var fastLoaded: Boolean = false
        private set

    fun load(name: String): Boolean {
        if (preferFast && hasI8mm) {
            if (runCatching { System.loadLibrary(name + "_i8mm") }.isSuccess) {
                fastLoaded = true
                return true
            }
        }
        return runCatching { System.loadLibrary(name) }.isSuccess
    }
}

/** Whisper(음성 인식) 네이티브 함수. 폰 안에서만 동작하고 네트워크를 쓰지 않는다. */
object WhisperNative {
    val available: Boolean = AiLibs.load("whisper_jni")

    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun transcribe(handle: Long, pcm16k: FloatArray, language: String, threads: Int, beam: Boolean): ByteArray?
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

/** llama.cpp(번역용 소형 AI 언어모델) 네이티브 함수. 폰 안에서만 동작. */
object LlamaNative {
    val available: Boolean = AiLibs.load("llama_jni")

    @JvmStatic external fun init(modelPath: String, nCtx: Int, threads: Int): Long
    @JvmStatic external fun generate(handle: Long, system: ByteArray, user: ByteArray, prefill: ByteArray, maxTokens: Int): ByteArray?
    /** [입력 토큰, 생성 토큰, 입력 처리 ms, 생성 ms, 스레드 수] */
    @JvmStatic external fun lastStats(handle: Long): LongArray
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

/** FFmpeg로 영상에서 소리만 꺼내기 (AVI·WMV·RMVB·FLV·MPG 등 모든 형식). */
object AudioNative {
    val available: Boolean = runCatching { System.loadLibrary("audio_jni") }.isSuccess

    @JvmStatic external fun open(source: String): Long
    @JvmStatic external fun durationMs(handle: Long): Long
    @JvmStatic external fun read(handle: Long, startMs: Long, lenMs: Long): FloatArray?
    @JvmStatic external fun close(handle: Long)
}
