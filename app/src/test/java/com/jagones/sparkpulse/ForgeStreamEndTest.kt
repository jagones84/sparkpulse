package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JAG-49 / v1.6.1 — the `done` event is the end of a stream.
 *
 * Regression guard for the "solo il primo messaggio funziona" symptom: the chat
 * stream must terminate on `done` (not on EOF), otherwise `busy` stays true,
 * STOP stays visible and the next message is dropped.
 */
class ForgeStreamEndTest {

    @Test
    fun `done is the terminal event`() {
        assertTrue(isTerminalSseEvent(TERMINAL_SSE_EVENT))
        assertEquals("done", TERMINAL_SSE_EVENT)
    }

    @Test
    fun `non terminal events do not end the stream`() {
        listOf("chat.run", "chat.delta", "graph.node.updated", "model.ready", "error", "")
            .forEach { assertFalse(it, isTerminalSseEvent(it)) }
    }

    @Test
    fun `done frame emitted by the server parses as terminal`() {
        val parser = SseLineParser()
        parser.feed("event: chat.delta")
        parser.feed("data: {\"text\":\"ciao\"}")
        val delta = parser.feed("")!!
        assertFalse(isTerminalSseEvent(delta.type))

        parser.feed("event: done")
        parser.feed("data: {}")
        val done = parser.feed("")!!
        assertTrue(isTerminalSseEvent(done.type))
    }
}
