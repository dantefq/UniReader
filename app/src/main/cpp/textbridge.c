/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Thin JNI bridge: Java supplies real UTF-8 bytes, never JNI modified UTF-8. */
#include "jni.h"
#define LOK_USE_UNSTABLE_API
#include "LibreOfficeKit.h"
extern void free(void*);

static LibreOfficeKitDocument* document(JNIEnv* env, jobject object) {
    if (!object) return NULL;
    jclass cls = (*env)->GetObjectClass(env, object);
    jfieldID field = (*env)->GetFieldID(env, cls, "handle", "Ljava/nio/ByteBuffer;");
    (*env)->DeleteLocalRef(env, cls);
    if (!field) return NULL;
    jobject handle = (*env)->GetObjectField(env, object, field);
    if (!handle) return NULL;
    LibreOfficeKitDocument* doc = (*env)->GetDirectBufferAddress(env, handle);
    (*env)->DeleteLocalRef(env, handle);
    return doc;
}
JNIEXPORT jboolean JNICALL Java_app_quietread_Utf8Bridge_paste(JNIEnv* env, jclass cls, jobject object, jbyteArray bytes) {
    (void)cls;
    LibreOfficeKitDocument* doc = document(env, object);
    if (!doc || !bytes || !doc->pClass || !LIBREOFFICEKIT_DOCUMENT_HAS(doc, paste)) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte* data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!data) return JNI_FALSE;
    bool result = doc->pClass->paste(doc, "text/plain;charset=utf-8", (const char*)data, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jbyteArray JNICALL Java_app_quietread_Utf8Bridge_selection(JNIEnv* env, jclass cls, jobject object) {
    (void)cls;
    LibreOfficeKitDocument* doc = document(env, object);
    if (!doc || !doc->pClass || !LIBREOFFICEKIT_DOCUMENT_HAS(doc, getTextSelection)) return NULL;
    char* used = NULL;
    char* text = doc->pClass->getTextSelection(doc, "text/plain;charset=utf-8", &used);
    free(used);
    if (!text) return NULL;
    size_t length = 0;
    while (text[length]) ++length;
    jbyteArray result = NULL;
    if (length <= 0x7fffffff) {
        result = (*env)->NewByteArray(env, (jsize)length);
        if (result && length) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte*)text);
    }
    free(text);
    return result;
}
