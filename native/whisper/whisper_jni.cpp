// Whisper(음성 인식) JNI 연결부 — 폰 안에서만 동작, 네트워크 사용 없음
#include <jni.h>
#include <chrono>
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

static long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
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
        JNIEnv *env, jclass, jlong ptr, jfloatArray jpcm, jstring jlang, jint threads, jboolean beam, jboolean live) {
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
    if (!live) {
        // 미리 만들기(꼼꼼): 애매하면 다시 풀어보되 횟수를 줄이고(0 → 0.4 → 0.8, 3번까지),
        // 같은 말 반복에 빠져도 한 줄이 끝없이 길어지지 않게 → 한 구간이 수 분~수십 분 걸리는 일 방지
        p.temperature_inc = 0.4f;
        p.max_tokens = 96;
    }
    if (live) {
        // 보면서 만들 때: 결과가 애매해도 다시 여러 번 풀어보지 않고(온도 올려 재시도 끔),
        // 같은 말 반복에 빠져도 한 줄이 너무 길어지지 않게 끊는다 → 한 구간이 수십 초~수 분 걸리는 일 방지
        p.temperature_inc = 0.0f;
        p.greedy.best_of = 1;
        p.max_tokens = 64;
    }
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = h;

    // 걸린 시간 재기: 소리 분석(인코딩) / 글자 만들기(디코딩) / 만든 글자 수
    struct Meter { long long t0 = 0, t_enc = 0, t_first = 0; long steps = 0; } m;
    m.t0 = now_ms();
    p.encoder_begin_callback = [](whisper_context *, whisper_state *, void *ud) -> bool {
        auto *mm = static_cast<Meter *>(ud);
        if (mm->t_enc == 0) mm->t_enc = now_ms();
        return true;
    };
    p.encoder_begin_callback_user_data = &m;
    p.logits_filter_callback = [](whisper_context *, whisper_state *, const whisper_token_data *, int, float *, void *ud) {
        auto *mm = static_cast<Meter *>(ud);
        if (mm->t_first == 0) mm->t_first = now_ms();
        mm->steps++;
    };
    p.logits_filter_callback_user_data = &m;

    if (whisper_full(h->ctx, p, pcm.data(), (int) pcm.size()) != 0) {
        return nullptr;
    }
    long long t_end = now_ms();
    long long enc_ms = (m.t_first ? m.t_first : t_end) - (m.t_enc ? m.t_enc : m.t0);
    long long dec_ms = m.t_first ? t_end - m.t_first : 0;

    std::string out;
    const char *detected = whisper_lang_str(whisper_full_lang_id(h->ctx));
    out += detected ? detected : "";
    // 첫 줄: 언어 \t 인코딩ms \t 디코딩ms \t 글자(토큰) 단계 수
    out += "\t" + std::to_string(enc_ms) + "\t" + std::to_string(dec_ms) + "\t" + std::to_string(m.steps);
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
