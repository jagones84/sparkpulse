package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseLineParserTest {

    @Test
    fun `complete event block yields event with id type and data`() {
        val parser = SseLineParser()
        assertNull(parser.feed("id: 42"))
        assertNull(parser.feed("event: agent.token"))
        assertNull(parser.feed("data: {\"text\":\"ciao\"}"))
        val event = parser.feed("")!!
        assertEquals("42", event.id)
        assertEquals("agent.token", event.type)
        assertEquals("{\"text\":\"ciao\"}", event.data)
        assertEquals("ciao", event.json()!!.optString("text"))
    }

    @Test
    fun `multiline data fields are joined with newline`() {
        val parser = SseLineParser()
        parser.feed("data: line1")
        parser.feed("data: line2")
        val event = parser.feed("")!!
        assertEquals("line1\nline2", event.data)
    }

    @Test
    fun `comment heartbeat lines are ignored`() {
        val parser = SseLineParser()
        assertNull(parser.feed(": ping"))
        assertNull(parser.feed(""))
        assertNull(parser.feed("data: only"))
        assertEquals("only", parser.feed("")!!.data)
    }

    @Test
    fun `blank lines between empty blocks do not emit events`() {
        val parser = SseLineParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed(": keepalive"))
        assertNull(parser.feed(""))
    }

    @Test
    fun `empty data blocks are suppressed, no event emitted`() {
        val parser = SseLineParser()
        assertNull(parser.feed("data"))
        assertNull(parser.feed(""))
        assertNull(parser.feed("data: "))
        assertNull(parser.feed(""))
    }

    @Test
    fun `parser resets type and id after each event`() {
        val parser = SseLineParser()
        parser.feed("id: 1")
        parser.feed("event: tick")
        parser.feed("data: a")
        assertEquals("tick", parser.feed("")!!.type)
        parser.feed("data: b")
        val second = parser.feed("")!!
        assertEquals("message", second.type)
        assertEquals(null, second.id)
        assertEquals("b", second.data)
    }
}