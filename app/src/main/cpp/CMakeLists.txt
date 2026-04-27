cmake_minimum_required(VERSION 3.18.1)
project("tun2socks_jni")

# Importar a libtun2socks.so como biblioteca externa
add_library(tun2socks SHARED IMPORTED)
set_target_properties(tun2socks PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtun2socks.so
)

# Nossa lib wrapper
add_library(tun2socks_jni SHARED tun2socks_jni.c)

# Linkar com a libtun2socks
find_library(log-lib log)
target_link_libraries(tun2socks_jni tun2socks ${log-lib})
