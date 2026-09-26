package com.jagones.sparkpulse

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class StatusSnapshot(
    val timestamp: Long? = null,
    val host: String? = null,
    val gpu: GpuStatus = GpuStatus(),
    val memory: MemoryStatus = MemoryStatus(),
    val model: ModelStatus = ModelStatus(),
    val topProcesses: List<GpuProcess> = emptyList(),
    val uptimeSeconds: Long? = null
)

data class GpuStatus(
    val utilization: Double? = null,
    val temperatureC: Double? = null,
    val powerW: Double? = null,
    val totalPowerW: Double? = null
)

data class MemoryStatus(
    val unifiedUsedGb: Double? = null,
    val unifiedTotalGb: Double? = null,
    val ramUsedGb: Double? = null,
    val ramTotalGb: Double? = null,
    val vramUsedGb: Double? = null
)

data class ModelStatus(
    val loaded: Boolean? = null,
    val name: String? = null,
    val vramGb: Double? = null,
    val pid: Long? = null
)

data class GpuProcess(
    val pid: Long? = null,
    val name: String? = null,
    val memoryGb: Double? = null
)

object StatusParser {
    fun parse(json: String): StatusSnapshot = try {
        val root = JSONObject(json)
        val gpu = root.objectOrEmpty("gpu")
        val memory = root.objectOrEmpty("memory")
        val model = root.objectOrEmpty("model")
        val processes = root.optJSONArray("top_procs").toProcesses()

        StatusSnapshot(
            timestamp = root.optLongOrNull("ts"),
            host = root.optStringOrNull("host"),
            gpu = GpuStatus(
                utilization = gpu.optDoubleOrNull("util"),
                temperatureC = gpu.optDoubleOrNull("temp"),
                powerW = gpu.optDoubleOrNull("power_w"),
                totalPowerW = gpu.optDoubleOrNull("total_w")
            ),
            memory = MemoryStatus(
                unifiedUsedGb = memory.optDoubleOrNull("unified_used_gb"),
                unifiedTotalGb = memory.optDoubleOrNull("unified_total_gb"),
                ramUsedGb = memory.optDoubleOrNull("ram_used_gb"),
                ramTotalGb = memory.optDoubleOrNull("ram_total_gb"),
                vramUsedGb = memory.optDoubleOrNull("vram_used_gb")
            ),
            model = ModelStatus(
                loaded = model.optBooleanOrNull("loaded"),
                name = model.optStringOrNull("name"),
                vramGb = model.optDoubleOrNull("vram_gb"),
                pid = model.optLongOrNull("pid")
            ),
            topProcesses = processes,
            uptimeSeconds = root.optLongOrNull("uptime_s")
        )
    } catch (_: JSONException) {
        StatusSnapshot()
    }

    private fun JSONObject.objectOrEmpty(key: String): JSONObject =
        optJSONObject(key) ?: JSONObject()

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Number -> value.toDouble().takeIf(Double::isFinite)
            is String -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
            else -> null
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        optDoubleOrNull(key)?.toLong()

    private fun JSONObject.optStringOrNull(key: String): String? =
        opt(key)?.takeIf { it is String } as? String

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        opt(key) as? Boolean

    private fun JSONArray?.toProcesses(): List<GpuProcess> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { process ->
                GpuProcess(
                    pid = process.optLongOrNull("pid"),
                    name = process.optStringOrNull("name"),
                    memoryGb = process.optDoubleOrNull("mem_gb")
                )
            }
        }.take(3)
    }
}
