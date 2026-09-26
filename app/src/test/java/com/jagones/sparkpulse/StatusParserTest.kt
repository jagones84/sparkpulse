package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusParserTest {
    @Test
    fun parsesTheContractFieldsAndLimitsProcessesToThree() {
        val status = StatusParser.parse(
            """
            {
              "ts": 1720000000,
              "host": "spark-6263",
              "gpu": {"util": 73, "temp": 61, "power_w": 92.5, "total_w": 120},
              "memory": {"unified_used_gb": 80.5, "unified_total_gb": 128, "ram_used_gb": 74, "ram_total_gb": 128, "vram_used_gb": 42},
              "model": {"loaded": true, "name": "qwen-local", "vram_gb": 42, "pid": 1234},
              "top_procs": [
                {"pid": 1, "name": "llama-server", "mem_gb": 40},
                {"pid": 2, "name": "python", "mem_gb": 5},
                {"pid": 3, "name": "comfyui", "mem_gb": 3},
                {"pid": 4, "name": "other", "mem_gb": 1}
              ],
              "uptime_s": 900
            }
            """.trimIndent()
        )

        assertEquals(73.0, status.gpu.utilization!!, 0.0)
        assertEquals(61.0, status.gpu.temperatureC!!, 0.0)
        assertEquals(92.5, status.gpu.powerW!!, 0.0)
        assertEquals(120.0, status.gpu.totalPowerW!!, 0.0)
        assertEquals(80.5, status.memory.unifiedUsedGb!!, 0.0)
        assertEquals(128.0, status.memory.unifiedTotalGb!!, 0.0)
        assertEquals("qwen-local", status.model.name)
        assertEquals(3, status.topProcesses.size)
        assertEquals("llama-server", status.topProcesses.first().name)
    }

    @Test
    fun missingOrInvalidFieldsStayNullWithoutCrashing() {
        val status = StatusParser.parse(
            """{"gpu":{"util":"unknown"},"memory":{},"model":{"loaded":false},"top_procs":[null,{}]}"""
        )

        assertNull(status.gpu.utilization)
        assertNull(status.gpu.totalPowerW)
        assertNull(status.gpu.temperatureC)
        assertNull(status.memory.unifiedUsedGb)
        assertNull(status.model.name)
        assertEquals(1, status.topProcesses.size)
        assertNull(status.topProcesses.single().name)
    }

    @Test
    fun emptyOrMalformedJsonReturnsAnEmptySnapshot() {
        assertEquals(StatusSnapshot(), StatusParser.parse("not-json"))
        assertEquals(StatusSnapshot(), StatusParser.parse(""))
    }
}
