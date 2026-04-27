#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdint.h>

#define TAG "tun2socks_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Ponteiros para as funções
typedef void (*start_fn)(int32_t, char*, int32_t);
typedef void (*stop_fn)();

static start_fn start_func = NULL;
static stop_fn stop_func = NULL;

// Inicializar - chamado quando a lib é carregada
__attribute__((constructor))
void init_tun2socks_jni() {
    // Procurar símbolos na libtun2socks.so já carregada
    void* handle = dlopen("libtun2socks.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        handle = dlopen(NULL, RTLD_NOW); // Procurar no espaço global
    }
    
    if (handle) {
        start_func = (start_fn)dlsym(handle, "StartTun2socks");
        stop_func = (stop_fn)dlsym(handle, "StopTun2socks");
        
        if (start_func && stop_func) {
            LOGI("✅ Símbolos Tun2Socks encontrados via dlsym!");
        } else {
            LOGE("❌ Símbolos não encontrados: start=%p stop=%p", start_func, stop_func);
        }
    } else {
        LOGE("❌ Não foi possível abrir libtun2socks.so: %s", dlerror());
    }
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StartTun2socks(JNIEnv* env, jclass clazz, jint tunFd, jstring socksAddr, jint mtu) {
    if (start_func) {
        const char* socks = (*env)->GetStringUTFChars(env, socksAddr, NULL);
        LOGI("▶️ StartTun2socks(fd=%d, socks=%s, mtu=%d)", tunFd, socks, mtu);
        start_func((int32_t)tunFd, (char*)socks, (int32_t)mtu);
        (*env)->ReleaseStringUTFChars(env, socksAddr, socks);
    } else {
        LOGE("❌ start_func é NULL! libtun2socks.so carregada?");
    }
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StopTun2socks(JNIEnv* env, jclass clazz) {
    if (stop_func) {
        LOGI("⏹️ StopTun2socks()");
        stop_func();
    }
}
