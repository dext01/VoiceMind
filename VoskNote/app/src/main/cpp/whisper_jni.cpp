#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "whisper.h"
#include <android/log.h>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static whisper_context* g_ctx = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_voicemind_WhisperHelper_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_ctx) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    LOGI("Model loaded OK, system_info: %s", whisper_print_system_info());
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_voicemind_WhisperHelper_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/,
        jshortArray pcmData, jstring initialPrompt) {

    if (!g_ctx) {
        LOGE("nativeTranscribe: context is null");
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(pcmData);
    if (len == 0) return env->NewStringUTF("");

    jshort* raw = env->GetShortArrayElements(pcmData, nullptr);
    std::vector<float> pcmF(len);
    for (int i = 0; i < len; i++) {
        pcmF[i] = (float)raw[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcmData, raw, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = 6;
    params.language         = nullptr;  // auto-detect
    params.translate        = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.single_segment   = false;
    params.max_tokens       = 0;

    const char* prompt = nullptr;
    if (initialPrompt) {
        prompt = env->GetStringUTFChars(initialPrompt, nullptr);
        if (prompt && strlen(prompt) > 0) {
            params.initial_prompt = prompt;
            LOGI("Using context prompt (%zu chars)", strlen(prompt));
        }
    }

    LOGI("Transcribing %d samples (%.1f sec)...", len, len / 16000.0f);
    int rc = whisper_full(g_ctx, params, pcmF.data(), (int)pcmF.size());

    if (prompt) env->ReleaseStringUTFChars(initialPrompt, prompt);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    int nSeg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nSeg; i++) {
        const char* seg = whisper_full_get_segment_text(g_ctx, i);
        if (seg) result += seg;
    }

    // strip leading space that Whisper often adds
    if (!result.empty() && result[0] == ' ') result = result.substr(1);

    LOGI("Done. Segments=%d, chars=%zu", nSeg, result.size());
    return env->NewStringUTF(result.c_str());
}

// Returns segments as a tab-delimited string: "startMs\tendMs\ttext\n" per segment.
// Timestamps from whisper are in 10ms units → multiply by 10 for milliseconds.
JNIEXPORT jstring JNICALL
Java_com_example_voicemind_WhisperHelper_nativeTranscribeWithSegments(
        JNIEnv* env, jobject /*thiz*/,
        jshortArray pcmData, jstring initialPrompt) {

    if (!g_ctx) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(pcmData);
    if (len == 0) return env->NewStringUTF("");

    jshort* raw = env->GetShortArrayElements(pcmData, nullptr);
    std::vector<float> pcmF(len);
    for (int i = 0; i < len; i++) pcmF[i] = (float)raw[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcmData, raw, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = 6;
    params.language         = nullptr;
    params.translate        = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.single_segment   = false;
    params.max_tokens       = 0;

    const char* prompt = nullptr;
    if (initialPrompt) {
        prompt = env->GetStringUTFChars(initialPrompt, nullptr);
        if (prompt && strlen(prompt) > 0) params.initial_prompt = prompt;
    }

    int rc = whisper_full(g_ctx, params, pcmF.data(), (int)pcmF.size());
    if (prompt) env->ReleaseStringUTFChars(initialPrompt, prompt);

    if (rc != 0) { LOGE("whisper_full failed: %d", rc); return env->NewStringUTF(""); }

    std::string result;
    int nSeg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nSeg; i++) {
        int64_t t0 = whisper_full_get_segment_t0(g_ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(g_ctx, i);
        const char* seg = whisper_full_get_segment_text(g_ctx, i);
        if (!seg) continue;
        // format: startMs \t endMs \t text \n
        result += std::to_string(t0 * 10) + "\t"
               +  std::to_string(t1 * 10) + "\t"
               +  seg + "\n";
    }
    LOGI("Segments: %d", nSeg);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_voicemind_WhisperHelper_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Model freed");
    }
}

} // extern "C"
