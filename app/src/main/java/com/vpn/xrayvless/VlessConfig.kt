package com.vpn.xrayvless

import com.google.gson.annotations.SerializedName

data class VlessConfig(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("server") val server: String,
    @SerializedName("port") val port: Int,
    @SerializedName("encryption") val encryption: String = "none",
    @SerializedName("security") val security: String = "none",
    @SerializedName("type") val type: String = "tcp",
    @SerializedName("flow") val flow: String = "",
    @SerializedName("remark") val remark: String = "XRAY VLESS VPN"
)
