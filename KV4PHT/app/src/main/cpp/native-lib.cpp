#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <stdlib.h>
#include <string.h>
extern "C" {
#include "robot36_decode/robot36_decode.h"
}

extern "C" int robot36_encode_wav_11025(const uint32_t *pixels, int w, int h, uint8_t **wav_out, int *wav_len);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_isNativeAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_encodeRobot36ToWav(JNIEnv* env, jclass, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    uint32_t *argb = (uint32_t*)pixels;
    uint8_t *wav = nullptr; int wav_len = 0;
    int rc = robot36_encode_wav_11025(argb, info.width, info.height, &wav, &wav_len);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (rc != 0 || wav == nullptr || wav_len <= 0) return nullptr;
    jbyteArray out = env->NewByteArray(wav_len);
    env->SetByteArrayRegion(out, 0, wav_len, (jbyte*)wav);
    free(wav);
    return out;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_decodeRobot36ToBitmap(JNIEnv* env, jclass, jfloatArray /*samples*/, jint /*sampleRate*/) {
    return nullptr;
}

static r36dec_ctx* g_ctx = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_startRobot36Decoder(JNIEnv* env, jclass, jint sampleRate) {
    if (g_ctx) { r36dec_destroy(g_ctx); g_ctx = nullptr; }
    g_ctx = r36dec_create(sampleRate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_feedRobot36Samples(JNIEnv* env, jclass, jfloatArray samples, jint sampleRate) {
    if (!g_ctx || !samples) return;
    jsize n = env->GetArrayLength(samples);
    jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
    r36dec_feed(g_ctx, (const float*)ptr, (int)n, (int)sampleRate);
    env->ReleaseFloatArrayElements(samples, ptr, JNI_ABORT);
}

static jobject create_bitmap(JNIEnv* env) {
    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID createMID = env->GetStaticMethodID(bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jfieldID argbFid = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(cfgCls, argbFid);
    jobject bmp = env->CallStaticObjectMethod(bitmapCls, createMID, 320, 256, argb);
    return bmp;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_getRobot36Progress(JNIEnv* env, jclass) {
    if (!g_ctx) return nullptr;
    jobject bmp = create_bitmap(env);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    uint32_t* src = r36dec_get_argb(g_ctx);
    memcpy(pixels, src, 320 * 256 * 4);
    AndroidBitmap_unlockPixels(env, bmp);
    int line = r36dec_get_line_index(g_ctx);
    const char* st = r36dec_get_state(g_ctx);
    int completed = r36dec_is_completed(g_ctx);
    jclass progCls = env->FindClass("com/vagell/kv4pht/radio/sstv/Robot36Progress");
    jmethodID ctor = env->GetMethodID(progCls, "<init>", "(ILandroid/graphics/Bitmap;Ljava/lang/String;Z)V");
    jstring jst = env->NewStringUTF(st);
    jobject prog = env->NewObject(progCls, ctor, (jint)line, bmp, jst, (jboolean)completed);
    env->DeleteLocalRef(jst);
    return prog;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vagell_kv4pht_radio_sstv_NativeSSTV_stopRobot36Decoder(JNIEnv*, jclass) {
    if (g_ctx) { r36dec_destroy(g_ctx); g_ctx = nullptr; }
}