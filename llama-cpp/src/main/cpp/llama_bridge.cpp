// llama_bridge.cpp
//
// Minimal JNI bridge exposing llama.cpp text generation to Kotlin.
// Loads a local .gguf model (e.g. gemma-3-1b-it.Q4_K_M.gguf) fully on-device
// and runs greedy/sampled decoding with NO network access whatsoever.
//
// Mirrors the shape of llama.cpp's own examples/llama.android JNI bridge,
// trimmed down to what Aladdin's LlamaCppEngine.kt needs: init / complete /
// completeStreaming / free.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>

#include "llama.h"
#include "common.h"

#define TAG "llama_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct AladdinLlamaContext {
    llama_model   *model  = nullptr;
    llama_context *ctx    = nullptr;
    llama_sampler *sampler = nullptr;
    int n_ctx = 2048;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aladdin_llamacpp_LlamaCppEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath, jint nCtx, jint nThreads, jint nGpuLayers) {

    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = nGpuLayers; // 0 = CPU only, safe default on-device

    llama_model *model = llama_model_load_from_file(modelPath, modelParams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!model) {
        LOGE("Failed to load GGUF model");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = nCtx;
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;

    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (!ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    // Simple greedy-with-temperature sampler chain (temp -> top_k -> top_p -> dist)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto *aladdinCtx = new AladdinLlamaContext();
    aladdinCtx->model = model;
    aladdinCtx->ctx = ctx;
    aladdinCtx->sampler = sampler;
    aladdinCtx->n_ctx = nCtx;

    LOGI("llama.cpp model loaded on-device: %s (n_ctx=%d, threads=%d)", modelPath, nCtx, nThreads);
    return reinterpret_cast<jlong>(aladdinCtx);
}

JNIEXPORT jstring JNICALL
Java_com_aladdin_llamacpp_LlamaCppEngine_nativeComplete(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jPrompt, jint maxTokens) {

    auto *aladdinCtx = reinterpret_cast<AladdinLlamaContext *>(handle);
    if (!aladdinCtx) return env->NewStringUTF("");

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab *vocab = llama_model_get_vocab(aladdinCtx->model);

    // Tokenize prompt
    int nPromptTokens = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                                         nullptr, 0, true, true);
    std::vector<llama_token> tokens(nPromptTokens);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                   tokens.data(), (int) tokens.size(), true, true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());

    std::string result;
    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(aladdinCtx->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }

        llama_token newToken = llama_sampler_sample(aladdinCtx->sampler, aladdinCtx->ctx, -1);
        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);

        batch = llama_batch_get_one(&newToken, 1);
    }

    return env->NewStringUTF(result.c_str());
}

// Streaming variant: invokes a Kotlin callback (LlamaCppEngine$TokenCallback)
// once per generated token so the AI response can start speaking (via Piper)
// before the whole completion has finished — matching the low-latency
// Mic -> STT -> LLM -> TTS pipeline the app targets.
JNIEXPORT void JNICALL
Java_com_aladdin_llamacpp_LlamaCppEngine_nativeCompleteStreaming(
        JNIEnv *env, jobject thiz,
        jlong handle, jstring jPrompt, jint maxTokens, jobject callback) {

    auto *aladdinCtx = reinterpret_cast<AladdinLlamaContext *>(handle);
    if (!aladdinCtx) return;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab *vocab = llama_model_get_vocab(aladdinCtx->model);

    int nPromptTokens = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                                         nullptr, 0, true, true);
    std::vector<llama_token> tokens(nPromptTokens);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                   tokens.data(), (int) tokens.size(), true, true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());

    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(aladdinCtx->ctx, batch) != 0) break;

        llama_token newToken = llama_sampler_sample(aladdinCtx->sampler, aladdinCtx->ctx, -1);
        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            jstring jToken = env->NewStringUTF(std::string(buf, n).c_str());
            jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMethod, jToken);
            env->DeleteLocalRef(jToken);
            if (!keepGoing) break;
        }

        batch = llama_batch_get_one(&newToken, 1);
    }
}

JNIEXPORT void JNICALL
Java_com_aladdin_llamacpp_LlamaCppEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *aladdinCtx = reinterpret_cast<AladdinLlamaContext *>(handle);
    if (!aladdinCtx) return;
    if (aladdinCtx->sampler) llama_sampler_free(aladdinCtx->sampler);
    if (aladdinCtx->ctx) llama_free(aladdinCtx->ctx);
    if (aladdinCtx->model) llama_model_free(aladdinCtx->model);
    delete aladdinCtx;
}

} // extern "C"
