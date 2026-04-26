package com.vpn.xrayvless

data class VlessConfig(
    val uuid: String = "",
    val server: String = "",
    val port: Int = 443,
    val encryption: String = "none",
    val security: String = "none",
    val type: String = "tcp",
    val flow: String = "",
    val remark: String = "XRAY VLESS VPN",
    val host: String = "",
    val path: String = "/",
    val sni: String = "",
    val mode: String = "auto",
    val alpn: String = "",
    val insecure: Boolean = false,
    val fp: String = "chrome",
    val allowInsecure: Boolean = false
)
