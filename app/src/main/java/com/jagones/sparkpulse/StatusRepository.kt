package com.jagones.sparkpulse

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class StatusRepository {
    fun fetch(host: String): StatusSnapshot {
        val normalizedHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require(normalizedHost.isNotBlank()) { "Host is empty" }
        val connection = URL("http://$normalizedHost:8787/status.json").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            StatusParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun fetchHistory(host: String): List<TelemetryPoint> = request(host, "/api/history?window_s=300") {
        TelemetryParser.parseHistory(it)
    }

    fun fetchModels(host: String): List<ModelOption> = request(host, "/api/models") {
        TelemetryParser.parseModels(it)
    }

    fun switchModel(host: String, alias: String) {
        require(alias.isNotBlank()) { "Model alias is empty" }
        val payload = JSONObject().put("action", "switch_model").put("model", alias).toString()
        request(host, "/api/commands", "POST", payload) { Unit }
    }

    private fun <T> request(
        host: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        parse: (String) -> T
    ): T {
        val normalizedHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require(normalizedHost.isNotBlank()) { "Host is empty" }
        val connection = URL("http://$normalizedHost:8787$path").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
