package com.jagones.sparkpulse

import java.net.HttpURLConnection
import java.net.URL

class StatusRepository {
    fun fetch(host: String): StatusSnapshot {
        val normalizedHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require(normalizedHost.isNotBlank()) { "Host is empty" }
        val connection = URL("http://$normalizedHost:8787/status.json").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            StatusParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
