package io.ladderairport.agent.util

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder

data class PairingInfo(
    val panelUrl: String,
    val nodeId: String,
    val token: String
)

object QrCodeParser {

    fun parse(raw: String?): PairingInfo? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        // 1. Try parsing JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                val element = JsonParser.parseString(text)
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    val panelUrl = getOptString(obj, "panel_url", "panelUrl")
                    val nodeId = getOptString(obj, "node_id", "nodeId")
                    val token = getOptString(obj, "token", "enroll_token", "enrollToken")

                    if (panelUrl.isNotBlank() && nodeId.isNotBlank() && token.isNotBlank()) {
                        return PairingInfo(panelUrl, nodeId, token)
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Try parsing URI: ladder://enroll?... or http(s)://...?...
        try {
            val uri = URI(text)
            val scheme = uri.scheme?.lowercase()
            val query = uri.rawQuery ?: ""
            val queryMap = parseQuery(query)

            if (scheme == "ladder" || scheme == "ladder-agent" || scheme == "http" || scheme == "https") {
                val panelUrl = queryMap["panel_url"]
                    ?: queryMap["panelUrl"]
                    ?: if (scheme == "http" || scheme == "https") "${uri.scheme}://${uri.authority}" else null
                val nodeId = queryMap["node_id"] ?: queryMap["nodeId"]
                val token = queryMap["token"]
                    ?: queryMap["enroll_token"]
                    ?: queryMap["enrollToken"]

                if (!panelUrl.isNullOrBlank() && !nodeId.isNullOrBlank() && !token.isNullOrBlank()) {
                    return PairingInfo(panelUrl.trim(), nodeId.trim(), token.trim())
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun getOptString(obj: com.google.gson.JsonObject, vararg keys: String): String {
        for (k in keys) {
            if (obj.has(k) && !obj.get(k).isJsonNull) {
                val v = obj.get(k).asString.trim()
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }
}
