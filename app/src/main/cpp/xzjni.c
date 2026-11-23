// Natywny dekoder XZ (liblzma) dla operacji REPLACE_XZ z payload.bin.
// Caly strumien .xz jest w pamieci (jedna operacja, zwykle ~2 MB po rozpakowaniu),
// wiec wystarcza lzma_stream_buffer_decode: bufor -> bufor, z weryfikacja sumy kontrolnej XZ.
#include <jni.h>
#include <stdint.h>
#include <lzma.h>

// Zwraca liczbe zdekodowanych bajtow albo -(kod lzma_ret) przy bledzie.
JNIEXPORT jint JNICALL
Java_pl_luki_frankenflasher_ota_NativeXz_decode(JNIEnv *env, jclass cls, jbyteArray in, jint inLen, jbyteArray out) {
    (void) cls;
    jsize outLen = (*env)->GetArrayLength(env, out);
    if (inLen < 0 || inLen > (*env)->GetArrayLength(env, in)) return -100;
    jbyte *src = (*env)->GetByteArrayElements(env, in, NULL);
    if (src == NULL) return -101;
    jbyte *dst = (*env)->GetByteArrayElements(env, out, NULL);
    if (dst == NULL) { (*env)->ReleaseByteArrayElements(env, in, src, JNI_ABORT); return -102; }
    uint64_t memlimit = UINT64_MAX;
    size_t in_pos = 0, out_pos = 0;
    lzma_ret r = lzma_stream_buffer_decode(&memlimit, 0, NULL,
        (const uint8_t *) src, &in_pos, (size_t) inLen, (uint8_t *) dst, &out_pos, (size_t) outLen);
    (*env)->ReleaseByteArrayElements(env, in, src, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, dst, r == LZMA_OK ? 0 : JNI_ABORT);
    return r == LZMA_OK ? (jint) out_pos : -(jint) r;
}

JNIEXPORT jstring JNICALL
Java_pl_luki_frankenflasher_ota_NativeXz_version(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, lzma_version_string());
}
