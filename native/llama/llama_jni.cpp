// llama.cpp(번역용 소형 AI 언어모델) JNI 연결부 — 폰 안에서만 동작, 네트워크 사용 없음
#include <jni.h>
#include <atomic>
#include <chrono>
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
    // 마지막 generate 통계: 입력 토큰 수, 생성 토큰 수, 입력 처리 ms, 생성 ms
    long long st_prompt = 0, st_gen = 0, st_prompt_ms = 0, st_gen_ms = 0;
    int threads = 0;
    // KV 캐시에 이미 들어 있는 토큰들 (앞부분이 같으면 다시 계산하지 않음)
    std::vector<llama_token> cached;
};

static long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

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

/**
 * 토큰들을 모델에 넣는다. 지난번과 앞부분이 같으면 그 부분은 건너뛰고 달라진 뒤쪽만 계산한다.
 * (번역 지시문은 매번 같아서, 이것만으로 입력 처리 시간이 크게 준다)
 * 성공하면 새로 계산한 토큰 수, 실패하면 -1.
 */
static int eval_tokens(LlmHandle *h, const std::vector<llama_token> &toks) {
    int n = (int) toks.size();
    int p = 0;
    int lim = std::min((int) h->cached.size(), n - 1); // 마지막 토큰은 늘 다시 넣어야 다음 단어를 고를 수 있음
    while (p < lim && h->cached[p] == toks[p]) p++;
    if (p == 0) {
        llama_kv_self_clear(h->ctx);
    } else if (!llama_kv_self_seq_rm(h->ctx, 0, p, -1)) {
        llama_kv_self_clear(h->ctx);
        p = 0;
    }
    h->cached.assign(toks.begin(), toks.begin() + p);
    for (int i = p; i < n; i += h->n_batch) {
        if (h->abort.load()) return -1;
        int cnt = std::min(h->n_batch, n - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(toks.data()) + i, cnt);
        if (llama_decode(h->ctx, batch) != 0) {
            h->cached.clear();
            llama_kv_self_clear(h->ctx);
            return -1;
        }
        h->cached.insert(h->cached.end(), toks.begin() + i, toks.begin() + i + cnt);
    }
    return n - p;
}

static bool tokenize(LlmHandle *h, const std::string &text, std::vector<llama_token> &out) {
    int n = -llama_tokenize(h->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n <= 0) return false;
    out.resize(n);
    return llama_tokenize(h->vocab, text.c_str(), (int32_t) text.size(), out.data(), n, true, true) >= 0;
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
    // 앱 전용 외부 폴더는 안드로이드 FUSE를 거쳐 mmap이 매우 느릴 수 있어 처음부터 메모리에 올린다
    mp.use_mmap = false;
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
    h->threads = threads;
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

    std::vector<llama_token> toks;
    if (!tokenize(h, prompt, toks)) return nullptr;
    int n = (int) toks.size();
    if (n + maxTokens > h->n_ctx) return nullptr;

    llama_sampler_reset(h->smpl);

    long long t0 = now_ms();
    int fresh = eval_tokens(h, toks);
    if (fresh < 0) return nullptr;

    long long t1 = now_ms();
    std::string out;
    char piece[256];
    int gen = 0;
    for (int i = 0; i < maxTokens; ++i) {
        if (h->abort.load()) break;
        llama_token tok = llama_sampler_sample(h->smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int m = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, m);
        llama_batch batch = llama_batch_get_one(&tok, 1);
        gen++;
        if (llama_decode(h->ctx, batch) != 0) break;
    }
    h->st_prompt = fresh;
    h->st_gen = gen;
    h->st_prompt_ms = t1 - t0;
    h->st_gen_ms = now_ms() - t1;
    return to_bytes(env, out);
}

/** 마지막 generate 통계 [입력 토큰, 생성 토큰, 입력 처리 ms, 생성 ms, 스레드 수]. */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_lastStats(JNIEnv *env, jclass, jlong ptr) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    jlongArray arr = env->NewLongArray(5);
    if (!h) return arr;
    jlong v[5] = {h->st_prompt, h->st_gen, h->st_prompt_ms, h->st_gen_ms, h->threads};
    env->SetLongArrayRegion(arr, 0, 5, v);
    return arr;
}

/**
 * 예열: 번역 지시문(시스템 부분)을 미리 넣어 둔다.
 * 처음 한 번은 폰이 계산 준비를 하느라 유난히 느린데, 그걸 모델 열 때 끝내 두고
 * 지시문도 캐시에 남아 첫 번역부터 빨라진다. 걸린 ms, 실패면 -1.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_warmup(JNIEnv *env, jclass, jlong ptr, jbyteArray jsys) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return -1;
    h->abort.store(false);
    std::string sys = jstr(env, jsys);
    std::string text = "<|im_start|>system\n" + sys + "<|im_end|>\n<|im_start|>user\n";
    std::vector<llama_token> toks;
    if (!tokenize(h, text, toks)) return -1;
    long long t0 = now_ms();
    if (eval_tokens(h, toks) < 0) return -1;
    return now_ms() - t0;
}

/** 계산에 쓸 코어 수 바꾸기 (영상 재생 중엔 줄여서 재생과 CPU를 덜 다툼). */
extern "C" JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_LlamaNative_setThreads(JNIEnv *, jclass, jlong ptr, jint n) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h || n <= 0 || h->threads == n) return;
    llama_set_n_threads(h->ctx, n, n);
    h->threads = n;
}
