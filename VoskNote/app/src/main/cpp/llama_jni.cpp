#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.cpp/include/llama.h"

#define TAG  "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*        g_model = nullptr;
static llama_context*      g_ctx   = nullptr;
static const llama_vocab*  g_vocab = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_voicemind_LlmHelper_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads) {

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
    g_vocab = nullptr;

    llama_backend_init();

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU-only (NEON) — Vulkan too slow on Adreno
    LOGI("GPU layers requested: %d", mparams.n_gpu_layers);

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = (uint32_t)nCtx;
    cparams.n_threads      = (uint32_t)nThreads;
    cparams.n_threads_batch= (uint32_t)nThreads; // все потоки на обработку промта
    cparams.n_batch        = 512;                 // батч промта
    cparams.n_ubatch       = 512;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) { LOGE("Failed to create context"); return JNI_FALSE; }

    LOGI("Model ready. vocab=%d n_ctx=%d threads=%d flash_attn=on",
         llama_vocab_n_tokens(g_vocab), nCtx, nThreads);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_voicemind_LlmHelper_nativeInfer(
        JNIEnv* env, jobject /*thiz*/, jstring promptStr, jint maxNewTokens) {

    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("nativeInfer: not initialized");
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(promptStr, nullptr);
    int promptLen = (int)strlen(prompt);

    // Tokenize
    std::vector<llama_token> tokens(promptLen + 64);
    int n = llama_tokenize(g_vocab, prompt, promptLen,
                           tokens.data(), (int)tokens.size(),
                           /*add_special=*/true, /*parse_special=*/true);
    env->ReleaseStringUTFChars(promptStr, prompt);

    if (n < 0) { LOGE("Tokenize failed (buffer too small)"); return env->NewStringUTF(""); }
    tokens.resize(n);
    LOGI("Prompt tokens: %d, max new: %d", n, maxNewTokens);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_ctx), /*data=*/false);

    // Sampler: repeat penalty → greedy
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    // penalty_last_n=64, repeat=1.15, freq=0.0, present=0.0
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // Encode the full prompt in one batch
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());

    std::string result;
    int nCur = 0;

    while (nCur < maxNewTokens) {
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed at token %d", nCur);
            break;
        }

        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, tok);

        if (llama_vocab_is_eog(g_vocab, tok)) break;

        char piece[256];
        int pieceLen = llama_token_to_piece(g_vocab, tok, piece, sizeof(piece), 0, true);
        if (pieceLen > 0) result.append(piece, pieceLen);

        batch = llama_batch_get_one(&tok, 1);
        nCur++;
    }

    llama_sampler_free(smpl);
    LOGI("Generated %d tokens → %zu chars", nCur, result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_voicemind_LlmHelper_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
    LOGI("LLM freed");
}

} // extern "C"
