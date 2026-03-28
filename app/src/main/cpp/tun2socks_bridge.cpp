#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include "tunbridge.h"

#define LOG_TAG "Tun2SocksBridge"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_bridge = nullptr;
static jmethodID g_protect_method = nullptr;
static std::mutex g_bridge_mutex;
static std::atomic<bool> g_running(false);

static bool protect_socket_callback(int socket_fd) {
    std::lock_guard<std::mutex> lock(g_bridge_mutex);
    if (g_vm == nullptr || g_bridge == nullptr || g_protect_method == nullptr) {
        ALOGE("Protect callback invoked before JNI bridge init");
        return false;
    }

    JNIEnv* env = nullptr;
    bool attached_here = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGE("Failed to attach JNI thread");
            return false;
        }
        attached_here = true;
    }

    jboolean protected_ok = env->CallBooleanMethod(g_bridge, g_protect_method, socket_fd);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        protected_ok = JNI_FALSE;
    }

    if (attached_here) {
        g_vm->DetachCurrentThread();
    }
    return protected_ok == JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_apidebug_inspector_nativebridge_Tun2SocksBridge_nativeStart(
    JNIEnv* env,
    jobject thiz,
    jint tun_fd,
    jint mtu,
    jstring socks5_address,
    jstring http_proxy_address,
    jboolean udp_enabled,
    jstring dns_resolver
) {
    std::lock_guard<std::mutex> lock(g_bridge_mutex);
    if (g_running.load()) {
        ALOGI("tun2socks already running");
        return JNI_TRUE;
    }

    const char* socks5 = env->GetStringUTFChars(socks5_address, nullptr);
    const char* http_proxy = env->GetStringUTFChars(http_proxy_address, nullptr);
    const char* dns = env->GetStringUTFChars(dns_resolver, nullptr);

    if (g_bridge != nullptr) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    g_bridge = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_protect_method = env->GetMethodID(clazz, "protectSocket", "(I)Z");
    tunbridge_set_protect_callback(protect_socket_callback);

    const bool started = tunbridge_start(
        static_cast<int>(tun_fd),
        static_cast<int>(mtu),
        socks5,
        http_proxy,
        udp_enabled == JNI_TRUE,
        dns
    );

    env->ReleaseStringUTFChars(socks5_address, socks5);
    env->ReleaseStringUTFChars(http_proxy_address, http_proxy);
    env->ReleaseStringUTFChars(dns_resolver, dns);

    g_running.store(started);
    if (!started) {
        ALOGE("tunbridge_start failed");
    }
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_apidebug_inspector_nativebridge_Tun2SocksBridge_nativeStop(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_bridge_mutex);
    tunbridge_stop();
    g_running.store(false);

    if (g_bridge != nullptr) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    g_protect_method = nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_apidebug_inspector_nativebridge_Tun2SocksBridge_nativeHasRealBackend(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return tunbridge_has_real_backend() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
