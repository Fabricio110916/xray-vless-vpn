#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <stdint.h>

#define TAG "tun2socks_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void (*start_fn)(int32_t, char*, int32_t);
typedef void (*stop_fn)();

static start_fn start_func = NULL;
static stop_fn stop_func = NULL;

__attribute__((constructor))
void init() {
    LOGI("init() - carregando libtun2socks...");
    void* h = dlopen("libtun2socks.so", RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        start_func = dlsym(h, "StartTun2socks");
        stop_func = dlsym(h, "StopTun2socks");
        LOGI("Símbolos: start=%p stop=%p", start_func, stop_func);
    } else {
        LOGE("dlopen: %s", dlerror());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_clearCloexec(JNIEnv* env, jclass clazz, jint fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags == -1) { LOGE("F_GETFD fd=%d: %s", fd, strerror(errno)); return JNI_FALSE; }
    if (fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC) == -1) { LOGE("F_SETFD fd=%d: %s", fd, strerror(errno)); return JNI_FALSE; }
    LOGI("FD_CLOEXEC limpo fd=%d", fd);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_dupFd(JNIEnv* env, jclass clazz, jint fd) {
    int newfd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (newfd == -1) { LOGE("dup fd=%d: %s", fd, strerror(errno)); return -1; }
    int flags = fcntl(newfd, F_GETFD);
    fcntl(newfd, F_SETFD, flags & ~FD_CLOEXEC);
    LOGI("fd=%d -> %d", fd, newfd);
    return newfd;
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_nativeStart(JNIEnv* env, jclass clazz, jint fd, jstring socks, jint mtu) {
    if (!start_func) { LOGE("start_func NULL!"); return; }
    const char* s = (*env)->GetStringUTFChars(env, socks, NULL);
    LOGI("StartTun2socks(fd=%d, socks=%s, mtu=%d)", fd, s, mtu);
    signal(SIGPIPE, SIG_IGN);
    start_func((int32_t)fd, (char*)s, (int32_t)mtu);
    LOGI("StartTun2socks retornou");
    (*env)->ReleaseStringUTFChars(env, socks, s);
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_nativeStop(JNIEnv* env, jclass clazz) {
    if (stop_func) { LOGI("StopTun2socks()"); stop_func(); }
}
