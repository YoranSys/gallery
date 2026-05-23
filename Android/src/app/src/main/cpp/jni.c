#include <jni.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_initContextFromFile(
        JNIEnv *env, jobject thiz, jstring modelPath) {
    UNUSED(thiz);

    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);
    if (path == NULL) {
        LOGW("initContextFromFile: GetStringUTFChars returned NULL");
        return 0;
    }

    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, modelPath, path);

    if (ctx == NULL) {
        LOGW("initContextFromFile: whisper_init_from_file_with_params returned NULL");
        return 0;
    }

    LOGI("initContextFromFile: context created successfully");
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong contextPtr) {
    UNUSED(env);
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("freeContext: contextPtr is null");
        return;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    whisper_free(ctx);
    LOGI("freeContext: context freed");
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong contextPtr, jint numThreads,
        jstring language, jfloatArray audioData) {
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("fullTranscribe: contextPtr is null");
        return;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;

    struct whisper_full_params params = whisper_full_default_params(
            WHISPER_SAMPLING_GREEDY);
    params.n_threads = (int)numThreads;
    params.no_context = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;

    const char *lang = "auto";
    if (language != NULL) {
        const char *jniLang = (*env)->GetStringUTFChars(env, language, NULL);
        if (jniLang != NULL) {
            lang = jniLang;
        }
    }
    params.language = lang;

    jfloat *samples = NULL;
    jsize n_samples = 0;
    if (audioData != NULL) {
        n_samples = (*env)->GetArrayLength(env, audioData);
        samples = (*env)->GetFloatArrayElements(env, audioData, NULL);
    }

    if (samples != NULL && n_samples > 0) {
        whisper_full(ctx, params, samples, n_samples);
        (*env)->ReleaseFloatArrayElements(env, audioData, samples, JNI_ABORT);
    } else {
        LOGW("fullTranscribe: no audio data provided");
    }

    if (language != NULL && lang != NULL && lang != "auto") {
        (*env)->ReleaseStringUTFChars(env, language, lang);
    }

    LOGI("fullTranscribe: transcription complete");
}

JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong contextPtr) {
    UNUSED(env);
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("getTextSegmentCount: contextPtr is null");
        return 0;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return (jint)whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong contextPtr, jint index) {
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("getTextSegment: contextPtr is null");
        return NULL;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    const char *text = whisper_full_get_segment_text(ctx, (int)index);
    if (text == NULL) {
        LOGW("getTextSegment: segment text is NULL at index %d", (int)index);
        return NULL;
    }
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong contextPtr, jint index) {
    UNUSED(env);
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("getTextSegmentT0: contextPtr is null");
        return 0;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return (jlong)whisper_full_get_segment_t0(ctx, (int)index);
}

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong contextPtr, jint index) {
    UNUSED(env);
    UNUSED(thiz);

    if (contextPtr == 0) {
        LOGW("getTextSegmentT1: contextPtr is null");
        return 0;
    }

    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return (jlong)whisper_full_get_segment_t1(ctx, (int)index);
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_voice_WhisperNative_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);

    const char *info = whisper_print_system_info();
    if (info == NULL) {
        LOGW("getSystemInfo: whisper_print_system_info returned NULL");
        return NULL;
    }
    return (*env)->NewStringUTF(env, info);
}
