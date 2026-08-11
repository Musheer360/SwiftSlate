package com.musheer360.swiftslate.provider

import java.net.URI

/**
 * Endpoint validation shared by the Settings screen and the OpenAI-compatible
 * client. https:// is unrestricted; cleartext http:// is permitted only for
 * private-LAN hosts (loopback, the Android emulator alias, RFC1918 private
 * ranges, link-local, and mDNS hostnames).
 *
 * Android's network security config cannot express IP ranges, so the platform
 * layer allows cleartext while this guard enforces the private-LAN rule at the
 * app level.
 */
object EndpointValidator {

    enum class Error { NONE, INVALID }

    /**
     * Validates [endpoint]: `Error.NONE` if it is a usable https:// URL or an
     * http:// URL pointing at a private-LAN host; `Error.INVALID` otherwise
     * (including blank, schemeless, or malformed values).
     */
    fun validate(endpoint: String): Error {
        if (endpoint.isBlank()) return Error.INVALID
        if (endpoint.startsWith("https://")) return Error.NONE
        if (!endpoint.startsWith("http://")) return Error.INVALID
        val host = try { URI(endpoint).host } catch (_: Exception) { return Error.INVALID }
        if (host.isNullOrEmpty() || !isPrivateHost(host)) return Error.INVALID
        return Error.NONE
    }

    /** Whether [host] is a private-LAN hostname or IP address. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().removeSuffix(".").lowercase()
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan")) return true
        val ip = h.toIPv4OrNull() ?: return false
        if (ip[0] == 127) return true // loopback 127.0.0.0/8
        if (ip[0] == 10) return true // RFC1918 10.0.0.0/8
        if (ip[0] == 172 && ip[1] in 16..31) return true // RFC1918 172.16.0.0/12
        if (ip[0] == 192 && ip[1] == 168) return true // RFC1918 192.168.0.0/16
        if (ip[0] == 169 && ip[1] == 254) return true // link-local 169.254.0.0/16
        return false
    }

    private fun String.toIPv4OrNull(): IntArray? {
        val parts = split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in parts.indices) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }
}
