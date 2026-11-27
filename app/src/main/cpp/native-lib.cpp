#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <mutex>
#include <atomic>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GILocalAI", __VA_ARGS__)
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_stop{false};
static int g_context_size = 1024;

extern "C" JNIEXPORT jstring JNICALL
Java_com_gi_localai_InferenceBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint context_size, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop.store(false);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // ARMv7 CPU-only
    mp.use_mmap = true;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) return env->NewStringUTF("ERROR: model load failed");

    llama_context_params cp = llama_context_default_params();
    g_context_size = context_size < 256 ? 256 : (context_size > 2048 ? 2048 : context_size);
    cp.n_ctx = (uint32_t) g_context_size;
    cp.n_batch = 128;
    cp.n_ubatch = 128;
    cp.n_threads = threads < 1 ? 1 : (threads > 4 ? 4 : threads);
    cp.n_threads_batch = cp.n_threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        return env->NewStringUTF("ERROR: context creation failed");
    }
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gi_localai_InferenceBridge_nativeGenerate(JNIEnv *env, jobject, jstring jprompt, jint max_tokens, jfloat temperature, jfloat top_p) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("ERROR: load a GGUF model first");
    g_stop.store(false);
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt = -llama_tokenize(vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt >= g_context_size - 8) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("ERROR: prompt too long for selected context");
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens.data(), n_prompt, true, true) < 0) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("ERROR: tokenization failed");
    }
    env->ReleaseStringUTFChars(jprompt, prompt);

    llama_kv_cache_clear(g_ctx);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: prompt decode failed");

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    const float safe_top_p = top_p < 0.1f ? 0.1f : (top_p > 1.0f ? 1.0f : top_p);
    const float safe_temp = temperature < 0.05f ? 0.05f : (temperature > 1.5f ? 1.5f : temperature);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(safe_top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(safe_temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    const int limit = max_tokens < 1 ? 1 : (max_tokens > 256 ? 256 : max_tokens);
    for (int i = 0; i < limit && !g_stop.load(); ++i) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
        llama_sampler_accept(sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[512];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);
        batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gi_localai_InferenceBridge_nativeStop(JNIEnv *, jobject) {
    g_stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gi_localai_InferenceBridge_nativeUnload(JNIEnv *, jobject) {
    g_stop.store(true);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
