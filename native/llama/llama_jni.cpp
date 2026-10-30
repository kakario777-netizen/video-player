// llama.cpp(번역용 소형 AI 언어모델) JNI 연결부 — 폰 안에서만 동작, 네트워크 사용 없음
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", __VA_ARGS__)
#else
#define LOGI(...)
#endif

struct LlmHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> abort{false};
};

static jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

static std::string jstr(JNIEnv *env, jbyteArray b) {
    jsize n = env->GetArrayLength(b);
    std::string s(n, '\0');
    env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_init(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint threads) {
    static bool backend = false;
    if (!backend) {
        llama_backend_init();
        backend = true;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // 같은 말 반복을 살짝 억제하고, 가장 확률 높은 단어를 고른다 (번역은 매번 같은 결과가 좋음)
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    auto *h = new LlmHandle();
    h->model = model;
    h->ctx = ctx;
    h->smpl = smpl;
    h->vocab = llama_model_get_vocab(model);
    h->n_ctx = (int) llama_n_ctx(ctx);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_abort(JNIEnv *, jclass, jlong ptr) {
    if (ptr) reinterpret_cast<LlmHandle *>(ptr)->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_free(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return;
    if (h->smpl) llama_sampler_free(h->smpl);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

/**
 * 시스템 지시 + 사용자 글을 chatml 형식으로 넣고 답을 만든다.
 * prefill: 답 앞에 미리 붙여둘 글 (Qwen3의 생각 모드를 끄는 빈 <think> 블록 등).
 * 입력·출력 모두 UTF-8 바이트 (한글 깨짐 방지). 실패하면 null.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_generate(
        JNIEnv *env, jclass, jlong ptr, jbyteArray jsys, jbyteArray juser, jbyteArray jprefill, jint maxTokens) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return nullptr;
    h->abort.store(false);

    std::string sys = jstr(env, jsys);
    std::string user = jstr(env, juser);
    std::string prefill = jstr(env, jprefill);

    llama_chat_message msgs[2] = {
            {"system", sys.c_str()},
            {"user", user.c_str()},
    };
    std::vector<char> buf(sys.size() + user.size() + 256);
    int len = llama_chat_apply_template("chatml", msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (len < 0) return nullptr;
    if (len > (int) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template("chatml", msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    std::string prompt(buf.data(), len);
    prompt += prefill;

    int n = -llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true) < 0) return nullptr;
    if (n + maxTokens > h->n_ctx) return nullptr;

    llama_kv_self_clear(h->ctx);
    llama_sampler_reset(h->smpl);

    for (int i = 0; i < n; i += h->n_batch) {
        if (h->abort.load()) return nullptr;
        int cnt = std::min(h->n_batch, n - i);
        llama_batch batch = llama_batch_get_one(toks.data() + i, cnt);
        if (llama_decode(h->ctx, batch) != 0) return nullptr;
    }

    std::string out;
    char piece[256];
    for (int i = 0; i < maxTokens; ++i) {
        if (h->abort.load()) break;
        llama_token tok = llama_sampler_sample(h->smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int m = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, m);
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->ctx, batch) != 0) break;
    }
    return to_bytes(env, out);
}
