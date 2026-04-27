#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <signal.h>

#define TAG "tun2socks_v2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void (*start_fn)(int32_t, char*, int32_t);
typedef void (*stop_fn)();

static start_fn start_func = NULL;
static stop_fn stop_func = NULL;

__attribute__((constructor))
void init_v2() {
    LOGI("=== V2 INIT ===");
    void* h = dlopen("libtun2socks.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { LOGE("dlopen: %s", dlerror()); return; }
    start_func = dlsym(h, "StartTun2socks");
    stop_func = dlsym(h, "StopTun2socks");
    LOGI("start=%p stop=%p", start_func, stop_func);
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StartTun2socks(JNIEnv* env, jclass clazz, jint fd, jstring socks, jint mtu) {
    LOGI(">>> V2 StartTun2socks fd=%d", fd);
    if (!start_func) { LOGE("start_func NULL!"); return; }
    
    const char* s = (*env)->GetStringUTFChars(env, socks, NULL);
    LOGI("Chamando start_func(%d, %s, %d)", fd, s, mtu);
    signal(SIGPIPE, SIG_IGN);
    start_func((int32_t)fd, (char*)s, (int32_t)mtu);
    LOGI("<<< start_func retornou");
    (*env)->ReleaseStringUTFChars(env, socks, s);
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StopTun2socks(JNIEnv* env, jclass clazz) {
    LOGI(">>> V2 StopTun2socks");
    if (stop_func) stop_func();
    LOGI("<<< V2 StopTun2socks");
}
