#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GILocalAI", __VA_ARGS__)
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::mutex g_mutex;

extern "C" JNIEXPORT jstring JNICALL
Java_com_gi_localai_InferenceBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // ARMv7 CPU-only baseline
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) return env->NewStringUTF("ERROR: model load failed");
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024;
    cp.n_threads = 2;
    cp.n_threads_batch = 2;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        return env->NewStringUTF("ERROR: context creation failed");
    }
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gi_localai_InferenceBridge_nativeGenerate(JNIEnv *env, jobject, jstring jprompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("ERROR: load a GGUF model first");
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt = -llama_tokenize(vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt > 900) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("ERROR: prompt too long or tokenization failed");
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens.data(), n_prompt, true, true) < 0) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("ERROR: tokenization failed");
    }
    env->ReleaseStringUTFChars(jprompt, prompt);
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("ERROR: prompt decode failed");
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string out;
    const int limit = max_tokens < 1 ? 1 : (max_tokens > 256 ? 256 : max_tokens);
    for (int i = 0; i < limit; ++i) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
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
Java_com_gi_localai_InferenceBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
