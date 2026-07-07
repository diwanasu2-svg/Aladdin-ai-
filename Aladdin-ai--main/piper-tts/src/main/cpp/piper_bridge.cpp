// piper_bridge.cpp
//
// Minimal JNI bridge exposing Piper neural TTS synthesis to Kotlin.
// Produces 16-bit PCM audio straight into a jshortArray so the Kotlin side
// can hand it directly to an AudioTrack — no subprocess exec, no writing to
// a temp file, no external `piper` binary needed (Android 10+ blocks
// executing arbitrary binaries outside nativeLibraryDir anyway, which is why
// this project uses a JNI library instead of the classic Piper CLI).

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "piper.hpp"

#define TAG "piper_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct AladdinPiperContext {
    piper::PiperConfig config;
    piper::Voice voice;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aladdin_pipertts_PiperTtsEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath, jstring jConfigPath, jstring jEspeakDataPath) {

    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *configPath = env->GetStringUTFChars(jConfigPath, nullptr);
    const char *espeakDataPath = env->GetStringUTFChars(jEspeakDataPath, nullptr);

    auto *aladdinCtx = new AladdinPiperContext();
    aladdinCtx->config.eSpeakDataPath = espeakDataPath;

    try {
        piper::initialize(aladdinCtx->config);
        piper::loadVoice(aladdinCtx->config, modelPath, configPath, aladdinCtx->voice,
                          std::optional<piper::SpeakerId>{}, false);
        LOGI("Piper voice loaded: %s (male voice, e.g. en_US-ryan-medium)", modelPath);
    } catch (const std::exception &e) {
        LOGE("Failed to load Piper voice: %s", e.what());
        delete aladdinCtx;
        env->ReleaseStringUTFChars(jModelPath, modelPath);
        env->ReleaseStringUTFChars(jConfigPath, configPath);
        env->ReleaseStringUTFChars(jEspeakDataPath, espeakDataPath);
        return 0;
    }

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jConfigPath, configPath);
    env->ReleaseStringUTFChars(jEspeakDataPath, espeakDataPath);

    return reinterpret_cast<jlong>(aladdinCtx);
}

// Synthesizes `text` and returns raw 16-bit PCM mono samples (typically
// 22050 Hz for *-medium Piper voices) as a jshortArray ready for AudioTrack.
JNIEXPORT jshortArray JNICALL
Java_com_aladdin_pipertts_PiperTtsEngine_nativeSynthesize(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle, jstring jText, jfloat lengthScale) {

    auto *aladdinCtx = reinterpret_cast<AladdinPiperContext *>(handle);
    if (!aladdinCtx) return env->NewShortArray(0);

    const char *textChars = env->GetStringUTFChars(jText, nullptr);
    std::string text(textChars);
    env->ReleaseStringUTFChars(jText, textChars);

    aladdinCtx->voice.synthesisConfig.lengthScale = lengthScale;

    std::vector<int16_t> audioBuffer;
    piper::SynthesisResult result;

    try {
        piper::textToAudio(aladdinCtx->config, aladdinCtx->voice, text, audioBuffer, result, nullptr);
    } catch (const std::exception &e) {
        LOGE("Piper synthesis failed: %s", e.what());
        return env->NewShortArray(0);
    }

    jshortArray out = env->NewShortArray((jsize) audioBuffer.size());
    env->SetShortArrayRegion(out, 0, (jsize) audioBuffer.size(),
                              reinterpret_cast<const jshort *>(audioBuffer.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_aladdin_pipertts_PiperTtsEngine_nativeSampleRate(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *aladdinCtx = reinterpret_cast<AladdinPiperContext *>(handle);
    if (!aladdinCtx) return 22050;
    return aladdinCtx->voice.synthesisConfig.sampleRate;
}

JNIEXPORT void JNICALL
Java_com_aladdin_pipertts_PiperTtsEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *aladdinCtx = reinterpret_cast<AladdinPiperContext *>(handle);
    if (!aladdinCtx) return;
    piper::terminate(aladdinCtx->config);
    delete aladdinCtx;
}

} // extern "C"
