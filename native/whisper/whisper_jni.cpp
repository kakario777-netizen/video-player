// Whisper(음성 인식) JNI 연결부 — 폰 안에서만 동작, 네트워크 사용 없음
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", __VA_ARGS__)
#else
#define LOGI(...)
#endif

struct WhisperHandle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

static bool abort_cb(void *user) {
    return static_cast<WhisperHandle *>(user)->abort.load();
}

static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kakario_videoplayer_ai_WhisperNative_init(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) return 0;
    auto *h = new WhisperHandle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_WhisperNative_abort(JNIEnv *, jclass, jlong ptr) {
    if (ptr) reinterpret_cast<WhisperHandle *>(ptr)->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_WhisperNative_free(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<WhisperHandle *>(ptr);
    if (!h) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

/**
 * 16kHz 모노 float 소리를 받아 인식한다.
 * 돌려주는 값(UTF-8 바이트): 첫 줄 = 감지한 언어 코드, 다음 줄부터 "시작ms\t끝ms\t문장".
 * 실패하면 null.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kakario_videoplayer_ai_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong ptr, jfloatArray jpcm, jstring jlang, jint threads, jboolean beam) {
    auto *h = reinterpret_cast<WhisperHandle *>(ptr);
    if (!h || !h->ctx) return nullptr;
    h->abort.store(false);

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    whisper_full_params p = whisper_full_default_params(beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.language = language.c_str();
    p.detect_language = false;
    p.n_threads = threads;
    p.no_context = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.suppress_nst = true;   // [음악] 같은 비음성 표시 줄이기
    if (beam) p.beam_search.beam_size = 5;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = h;

    if (whisper_full(h->ctx, p, pcm.data(), (int) pcm.size()) != 0) {
        return nullptr;
    }

    std::string out;
    const char *detected = whisper_lang_str(whisper_full_lang_id(h->ctx));
    out += detected ? detected : "";
    out += "\n";
    const int segs = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < segs; ++i) {
        int64_t t0 = whisper_full_get_segment_t0(h->ctx, i) * 10; // 10ms 단위 → ms
        int64_t t1 = whisper_full_get_segment_t1(h->ctx, i) * 10;
        std::string text = whisper_full_get_segment_text(h->ctx, i);
        for (auto &c : text) if (c == '\n' || c == '\r' || c == '\t') c = ' ';
        out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text + "\n";
    }
    return to_bytes(env, out);
}
