cmake_minimum_required(VERSION 3.18.1)
project("tun2socks_jni")

add_library(tun2socks_jni SHARED tun2socks_v2.c)
find_library(log-lib log)
target_link_libraries(tun2socks_jni ${log-lib})
