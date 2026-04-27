#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>

#define TAG "tun2socks_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jboolean JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_clearCloexec(JNIEnv* env, jclass clazz, jint fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags == -1) {
        LOGE("fcntl F_GETFD fd=%d failed: %s", fd, strerror(errno));
        return JNI_FALSE;
    }
    if (fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC) == -1) {
        LOGE("fcntl F_SETFD fd=%d failed: %s", fd, strerror(errno));
        return JNI_FALSE;
    }
    LOGI("FD_CLOEXEC cleared for fd=%d (flags: 0x%x -> 0x%x)", fd, flags, flags & ~FD_CLOEXEC);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_dupFd(JNIEnv* env, jclass clazz, jint fd) {
    int newfd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (newfd == -1) {
        LOGE("dup fd=%d failed: %s", fd, strerror(errno));
        return -1;
    }
    // Limpar CLOEXEC no novo fd
    int flags = fcntl(newfd, F_GETFD);
    fcntl(newfd, F_SETFD, flags & ~FD_CLOEXEC);
    LOGI("fd=%d duplicado para fd=%d", fd, newfd);
    return newfd;
}
