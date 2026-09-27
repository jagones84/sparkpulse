package com.jagones.sparkpulse

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class TelemetryPoint(
    val timestampSeconds: Long,
    val gpuUtilization: Double? = null,
    val gpuTemperatureC: Double? = null,
    val unifiedUsedGb: Double? = null
)

data class ModelOption(val alias: String, val loaded: Boolean)

object TelemetryParser {
    fun parseHistory(json: String): List<TelemetryPoint> = try {
        val rootValue: Any = org.json.JSONTokener(json).nextValue()
        val root = rootValue as? JSONObject ?: JSONObject()
        val samples = (rootValue as? JSONArray)
            ?: root.optJSONArray("samples")
            ?: root.optJSONArray("history")
            ?: root.optJSONArray("data")
            ?: JSONArray()
        val points = (0 until samples.length()).mapNotNull { index ->
            val sample = samples.optJSONObject(index) ?: return@mapNotNull null
            val gpu = sample.optJSONObject("gpu") ?: JSONObject()
            val memory = sample.optJSONObject("memory") ?: JSONObject()
            val timestamp = sample.optLongValue("ts")
                ?: sample.optLongValue("timestamp")
                ?: sample.optLongValue("time")
                ?: return@mapNotNull null
            TelemetryPoint(
                timestampSeconds = timestamp,
                gpuUtilization = gpu.optDoubleValue("util") ?: gpu.optDoubleValue("utilization")
                    ?: sample.optDoubleValue("gpu_util") ?: sample.optDoubleValue("gpu_utilization"),
                gpuTemperatureC = gpu.optDoubleValue("temp") ?: gpu.optDoubleValue("temperature_c")
                    ?: sample.optDoubleValue("gpu_temp_c") ?: sample.optDoubleValue("gpu_temperature_c"),
                unifiedUsedGb = memory.optDoubleValue("unified_used_gb")
                    ?: memory.optDoubleValue("uma_used_gb")
                    ?: sample.optDoubleValue("unified_used_gb")
                    ?: sample.optDoubleValue("uma_used_gb")
            )
        }.sortedBy { it.timestampSeconds }
        val latestTimestamp = points.lastOrNull()?.timestampSeconds
        if (latestTimestamp == null) emptyList()
        else {
            val cutoff = latestTimestamp - HISTORY_WINDOW_SECONDS
            points.filter { it.timestampSeconds >= cutoff }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun parseModels(json: String): List<ModelOption> = try {
        val root = JSONObject(json)
        val models = (root.optJSONArray("models")
            ?: root.optJSONArray("available")
            ?: root.optJSONObject("models")?.optJSONArray("items"))
            ?: JSONArray()
        (0 until models.length()).mapNotNull { index ->
            val model = models.optJSONObject(index) ?: return@mapNotNull null
            val alias = model.optStringValue("alias") ?: model.optStringValue("name")
                ?: return@mapNotNull null
            val state = model.optJSONObject("status") ?: model.optJSONObject("model") ?: JSONObject()
            val status = model.optStringValue("status") ?: model.optStringValue("state")
                ?: state.optStringValue("state")
            val loaded = model.optBooleanValue("loaded") ?: state.optBooleanValue("loaded") ?: when (status?.lowercase()) {
                "loaded", "active", "current" -> true
                "unloaded", "available", "inactive" -> false
                else -> return@mapNotNull null
            }
            ModelOption(alias, loaded)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private const val HISTORY_WINDOW_SECONDS = 300L

fun normalizePollIntervalMillis(value: Int): Long = when (value) {
    1_000, 5_000 -> value.toLong()
    else -> 2_000L
}

private fun JSONObject.optDoubleValue(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    return when (value) {
        is Number -> value.toDouble().takeIf(Double::isFinite)
        is String -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }
}

private fun JSONObject.optLongValue(key: String): Long? = optDoubleValue(key)?.toLong()

private fun JSONObject.optStringValue(key: String): String? =
    opt(key)?.takeIf { it is String } as? String

private fun JSONObject.optBooleanValue(key: String): Boolean? = opt(key) as? Boolean
