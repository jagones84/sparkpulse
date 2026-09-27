package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryParserTest {
    @Test
    fun parsesHistoryMetricsAndIgnoresSamplesWithMissingMetrics() {
        val history = TelemetryParser.parseHistory(
            """
            {
              "samples": [
                {"ts": 1720000000, "gpu": {"util": 73, "temp": 61}, "memory": {"unified_used_gb": 80.5}},
                {"ts": 1720000001, "gpu": {"util": 74}, "memory": {}}
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, history.size)
        assertEquals(1720000000L, history[0].timestampSeconds)
        assertEquals(73.0, history[0].gpuUtilization!!, 0.0)
        assertEquals(61.0, history[0].gpuTemperatureC!!, 0.0)
        assertEquals(80.5, history[0].unifiedUsedGb!!, 0.0)
        assertNull(history[1].gpuTemperatureC)
        assertNull(history[1].unifiedUsedGb)
    }

    @Test
    fun parsesModelAliasesAndLoadedStateWithoutAssumingAllFieldsExist() {
        val models = TelemetryParser.parseModels(
            """
            {"models": [
              {"alias": "qwen-q4", "status": "loaded"},
              {"alias": "qwen-q8", "loaded": false},
              {"name": "incomplete"}
            ]}
            """.trimIndent()
        )

        assertEquals(2, models.size)
        assertEquals("qwen-q4", models[0].alias)
        assertTrue(models[0].loaded)
        assertEquals("qwen-q8", models[1].alias)
        assertFalse(models[1].loaded)
    }

    @Test
    fun acceptsAlternateHistoryFieldNamesAndRootArrays() {
        val history = TelemetryParser.parseHistory(
            """
            [{"timestamp": 1720000000, "gpu_utilization": 73,
              "gpu_temperature_c": 61, "unified_used_gb": 80.5}]
            """.trimIndent()
        )

        assertEquals(1, history.size)
        assertEquals(73.0, history.single().gpuUtilization!!, 0.0)
        assertEquals(61.0, history.single().gpuTemperatureC!!, 0.0)
        assertEquals(80.5, history.single().unifiedUsedGb!!, 0.0)
    }

    @Test
    fun malformedHistoryReturnsNoPoints() {
        assertEquals(emptyList<TelemetryPoint>(), TelemetryParser.parseHistory("not-json"))
    }

    @Test
    fun historyIsSortedAndLimitedToFiveMinutes() {
        val history = TelemetryParser.parseHistory(
            """{"samples":[{"ts":900},{"ts":650},{"ts":700},{"ts":600}]}"""
        )
        assertEquals(listOf(600L, 650L, 700L, 900L), history.map { it.timestampSeconds })
    }

    @Test
    fun pollingIntervalOnlyAcceptsSupportedValuesAndDefaultsToTwoSeconds() {
        assertEquals(2_000L, normalizePollIntervalMillis(2_000))
        assertEquals(1_000L, normalizePollIntervalMillis(1_000))
        assertEquals(5_000L, normalizePollIntervalMillis(5_000))
        assertEquals(2_000L, normalizePollIntervalMillis(3_000))
    }

    @Test
    fun modelStateCanBeReadFromStatusAndNestedModelObjects() {
        val models = TelemetryParser.parseModels(
            """{"models":[{"alias":"q4","status":{"loaded":true}},{"alias":"q8","model":{"loaded":false}}]}"""
        )
        assertEquals(listOf(ModelOption("q4", true), ModelOption("q8", false)), models)
    }
}
