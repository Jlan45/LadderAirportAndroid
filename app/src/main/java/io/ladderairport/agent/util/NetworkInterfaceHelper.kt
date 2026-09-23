package io.ladderairport.agent.util

import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

object NetworkInterfaceHelper {

    fun getInterfacesJSON(): String {
        val array = JSONArray()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "[]"
            for (iface in interfaces) {
                val obj = JSONObject()
                obj.put("name", iface.name)
                obj.put("up", iface.isUp)
                obj.put("loopback", iface.isLoopback)
                obj.put("mtu", iface.mtu)

                val hwAddr = iface.hardwareAddress
                if (hwAddr != null && hwAddr.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (b in hwAddr) {
                        if (sb.isNotEmpty()) sb.append(":")
                        sb.append(String.format("%02x", b))
                    }
                    obj.put("hardware_addr", sb.toString())
                } else {
                    obj.put("hardware_addr", "")
                }

                val addrsArray = JSONArray()
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    val hostAddress = addr.hostAddress
                    if (!hostAddress.isNullOrBlank()) {
                        // Strip interface index / zone suffix if present
                        val clean = hostAddress.substringBefore("%")
                        addrsArray.put(clean)
                    }
                }
                obj.put("addresses", addrsArray)
                array.put(obj)
            }
        } catch (_: Exception) {
        }
        return array.toString()
    }
}
