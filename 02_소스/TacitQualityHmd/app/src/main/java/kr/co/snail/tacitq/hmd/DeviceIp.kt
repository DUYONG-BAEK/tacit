package kr.co.snail.tacitq.hmd

import java.net.Inet4Address
import java.net.NetworkInterface

/** wlan0 등 사설 IPv4 주소 — 상태줄(MainActivity)과 HELLO 영상 주소(LinkService)에 쓴다. */
internal fun deviceIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
