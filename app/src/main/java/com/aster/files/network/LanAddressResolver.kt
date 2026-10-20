package com.aster.files.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** 读取局域网 IPv4；优先 wlan 接口，不依赖定位权限。 */
object LanAddressResolver {
    @Suppress("DEPRECATION")
    fun resolve(context: Context): String? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val candidates = interfaces
            .filter { !it.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { address -> network.name to address.hostAddress }
            }
        candidates.firstOrNull { it.first.startsWith("wlan") }?.let { return it.second }
        candidates.firstOrNull()?.let { return it.second }
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val value = wifi.connectionInfo.ipAddress
            if (value == 0) null else String.format(
                "%d.%d.%d.%d",
                value and 0xff,
                value shr 8 and 0xff,
                value shr 16 and 0xff,
                value shr 24 and 0xff
            )
        }.getOrNull()
    }
}
