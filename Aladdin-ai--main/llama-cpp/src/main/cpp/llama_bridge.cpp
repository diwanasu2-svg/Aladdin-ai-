// llama_bridge.cpp
//
// Minimal JNI bridge exposing llama.cpp text generation to Kotlin.
// Loads a local .gguf model (e.g. gemma-3-1b-it.Q4_K_M.gguf) fully on-device
// and runs greedy/sampled decoding with NO network access whatsoever.
//
// Mirrors the shape of llama.cpp's own examples/llama.android JNI bridge,
// trimmed down to what Aladdin's LlamaCppEngine.kt needs: init / complete /
// completeStreaming / free.
//
// Bug fix (2026-07-07): the raw "User: ...\nAssistant: ..." prompt format has
// no way of telling the model to stop after ONE reply — with only the model's
// own end-of-generation token as a stop condition, gemma/llama-family models
// happily keep hallucinating an entire fake back-and-forth conversation
// ("User: what is ...\nAssistant: ...\nUser: ...") until `maxTokens` runs out.
// That is exactly what made replies feel like they "hang" for minutes and
// never really finish — the app was waiting for ~256 tokens of a fictional
// conversation instead of ~20-40 tokens for one real reply. We now detect a
// small set of turn-marker stop sequences as they stream out of the model and
// cut generation off immediately once one appears, same idea as llama.cpp's
// own `--reverse-prompt` / stop-sequence handling.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
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

namespace {

// Turn-marker / role-leak stop sequences. Any of these appearing in the
// model's OWN generated text (never in the prompt, which is stripped before
// we start scanning) means it has started hallucinating a new conversational
// turn instead of ending its answer — so we stop right there.
const std::vector<std::string> &stopMarkers() {
    static const std::vector<std::string> markers = {
        "\nUser:", "\nuser:", "\nUSER:", "User:",
        "\nAssistant:", "\nassistant:", "\nASSISTANT:", "Assistant:",
        "\nHuman:", "Human:",
        "<end_of_turn>", "<|im_end|>", "<|user|>", "<|assistant|>", "<|end|>"
    };
    return markers;
}

size_t longestMarkerLen() {
    static size_t len = [] {
        size_t m = 0;
        for (auto &s : stopMarkers()) m = std::max(m, s.size());
        return m;
    }();
    return len;
}

// Finds the earliest occurrence of any stop marker at/after `searchFrom`.
// Returns std::string::npos if none found.
size_t findEarliestStop(const std::string &full, size_t searchFrom) {
    size_t best = std::string::npos;
    for (auto &marker : stopMarkers()) {
        size_t from = searchFrom > marker.size() ? searchFrom - marker.size() : 0;
        size_t p = full.find(marker, from);
        if (p != std::string::npos && (best == std::string::npos || p < best)) best = p;
    }
    return best;
}

} // namespace

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

    std::string full;
    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(aladdinCtx->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }

        llama_token newToken = llama_sampler_sample(aladdinCtx->sampler, aladdinCtx->ctx, -1);
        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            full.append(buf, n);
            // Stop the moment the model starts hallucinating a new turn
            // ("\nUser:", "\nAssistant:", etc.) instead of ending its reply.
            size_t stopPos = findEarliestStop(full, full.size() >= (size_t) n ? full.size() - n : 0);
            if (stopPos != std::string::npos) {
                full.erase(stopPos);
                break;
            }
        }

        batch = llama_batch_get_one(&newToken, 1);
    }

    // Trim trailing whitespace left over from truncation.
    while (!full.empty() && (full.back() == '\n' || full.back() == ' ')) full.pop_back();

    return env->NewStringUTF(full.c_str());
}

// Streaming variant: invokes a Kotlin callback (LlamaCppEngine$TokenCallback)
// once per generated token so the AI response can start speaking (via Piper)
// before the whole completion has finished — matching the low-latency
// Mic -> STT -> LLM -> TTS pipeline the app targets.
//
// Tokens are held back briefly (a small tail buffer, `longestMarkerLen()`
// characters wide) before being forwarded to the callback/TTS, so that a
// stop marker split across multiple tokens can still be caught and silenced
// before it's ever spoken aloud or shown in the chat bubble.
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

    std::string full;          // everything generated so far
    size_t emitted = 0;        // how much of `full` has already gone to the callback
    const size_t holdBack = longestMarkerLen();

    auto flushUpTo = [&](size_t upTo) {
        if (upTo > emitted) {
            std::string chunk = full.substr(emitted, upTo - emitted);
            emitted = upTo;
            if (!chunk.empty()) {
                jstring jToken = env->NewStringUTF(chunk.c_str());
                env->CallBooleanMethod(callback, onTokenMethod, jToken);
                env->DeleteLocalRef(jToken);
            }
        }
    };

    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(aladdinCtx->ctx, batch) != 0) break;

        llama_token newToken = llama_sampler_sample(aladdinCtx->sampler, aladdinCtx->ctx, -1);
        if (llama_vocab_is_eog(vocab, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            full.append(buf, n);

            size_t stopPos = findEarliestStop(full, emitted);
            if (stopPos != std::string::npos) {
                // Flush only the safe part before the marker, then stop —
                // the hallucinated "User:"/"Assistant:" turn is never sent.
                flushUpTo(stopPos);
                batch = llama_batch_get_one(&newToken, 1); // keep var usage tidy
                break;
            }

            // No marker (yet). Keep the last `holdBack` chars unsent in case
            // a marker is still forming across the next token(s).
            if (full.size() > emitted + holdBack) {
                flushUpTo(full.size() - holdBack);
            }
        }

        batch = llama_batch_get_one(&newToken, 1);
    }

    // Generation ended naturally (EOG / max tokens) with no stop marker hit —
    // flush whatever safe text is still pending.
    if (findEarliestStop(full, emitted) == std::string::npos) {
        flushUpTo(full.size());
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
