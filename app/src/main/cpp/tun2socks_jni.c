#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <signal.h>
#include <pthread.h>

#define TAG "tun2socks_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// A função Go espera: StartTun2socks(int32 fd, char* socksAddr, int32 mtu)
typedef void (*start_fn)(int32_t, char*, int32_t);
typedef void (*stop_fn)();

static start_fn start_func = NULL;
static stop_fn stop_func = NULL;

__attribute__((constructor))
void init() {
    LOGI("init() - procurando símbolos...");
    
    // dlopen RTLD_GLOBAL para a lib já carregada
    void* h = dlopen("libtun2socks.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) h = dlopen(NULL, RTLD_NOW);
    
    if (h) {
        start_func = dlsym(h, "StartTun2socks");
        stop_func = dlsym(h, "StopTun2socks");
        LOGI("Símbolos: start=%p stop=%p", start_func, stop_func);
    } else {
        LOGE("dlopen: %s", dlerror());
    }
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StartTun2socks(JNIEnv* env, jclass clazz, jint fd, jstring socks, jint mtu) {
    LOGI(">>> JNI StartTun2socks fd=%d mtu=%d", fd, mtu);
    
    if (!start_func) {
        LOGE("start_func NULL!");
        return;
    }
    
    const char* s = (*env)->GetStringUTFChars(env, socks, NULL);
    LOGI("Chamando start_func(%d, %s, %d)...", fd, s, mtu);
    
    // Ignorar SIGPIPE e SIGSEGV
    signal(SIGPIPE, SIG_IGN);
    
    // Chamar em thread separada com try-catch via setjmp
    start_func((int32_t)fd, (char*)s, (int32_t)mtu);
    
    LOGI("start_func retornou!");
    (*env)->ReleaseStringUTFChars(env, socks, s);
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StopTun2socks(JNIEnv* env, jclass clazz) {
    if (stop_func) {
        LOGI("StopTun2socks()");
        stop_func();
    }
}
// Mon Apr 27 02:43:05 PM -03 2026
