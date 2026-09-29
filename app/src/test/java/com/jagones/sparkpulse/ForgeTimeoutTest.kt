package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.1 cold-start guard: the client must never abort a chat/agent run before
 * the LLM has had a chance to warm up (requirement: timeout >= 120 s).
 */
class ForgeTimeoutTest {

    @Test
    fun `cold-start floor is at least two minutes`() {
        assertTrue(FORGE_MIN_RUN_TIMEOUT_MS >= 120_000)
    }

    @Test
    fun `rest deadline stays above the cold-start floor`() {
        assertTrue(FORGE_REST_READ_TIMEOUT_MS >= FORGE_MIN_RUN_TIMEOUT_MS)
    }

    @Test
    fun `sse deadline is unbounded so a cold model is never cut off`() {
        assertEquals(0, FORGE_SSE_READ_TIMEOUT_MS)
    }

    @Test
    fun `selfcheck probe stays short so wrong host fails fast`() {
        assertTrue(SELFCHECK_TIMEOUT_MS in 1..30_000)
    }
}
