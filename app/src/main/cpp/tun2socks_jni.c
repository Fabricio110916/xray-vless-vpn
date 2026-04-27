#include <jni.h>
#include <stdint.h>

extern void StartTun2socks(int32_t tunFd, char* socksAddr, int32_t mtu);
extern void StopTun2socks();

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StartTun2socks(JNIEnv* env, jclass clazz, jint tunFd, jstring socksAddr, jint mtu) {
    const char* socks = (*env)->GetStringUTFChars(env, socksAddr, NULL);
    StartTun2socks((int32_t)tunFd, (char*)socks, (int32_t)mtu);
    (*env)->ReleaseStringUTFChars(env, socksAddr, socks);
}

JNIEXPORT void JNICALL
Java_com_vpn_xrayvless_Tun2SocksJNI_StopTun2socks(JNIEnv* env, jclass clazz) {
    StopTun2socks();
}
