package com.jagones.sparkpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5: parsers backing the sessions/tasks/history panels. */
class ForgeParseTest {

    @Test
    fun parsesSessionsList() {
        val body = """{"sessions":[
            {"id":"abc123","title":"session abc123","created":1790697824.5,"messages":4},
            {"id":"def456","title":"work","created":1790697681.4,"messages":0}
        ]}"""
        val sessions = parseSessions(body)
        assertEquals(2, sessions.size)
        assertEquals("abc123", sessions[0].id)
        assertEquals(4, sessions[0].messages)
        assertEquals("work", sessions[1].title)
    }

    @Test
    fun parsesTasksAndDefaultsStatus() {
        val body = """{"tasks":[
            {"id":"t1","title":"Define routine","status":"done"},
            {"id":"t2","title":"Iterazione 2/4"},
            {"id":"t3","title":"Iterazione 3/4","status":"doing"}
        ],"remaining":["todo: x"]}"""
        val tasks = parseTasks(body)
        assertEquals(3, tasks.size)
        assertEquals("done", tasks[0].status)
        assertEquals("todo", tasks[1].status) // missing status defaults to todo
        assertEquals("doing", tasks[2].status)
    }

    @Test
    fun parsesHistoryRolesAndReasoning() {
        val body = """{"id":"s1","title":"x","messages":[
            {"role":"user","content":"ciao","ts":1.0},
            {"role":"assistant","content":"ok","reasoning":"We need to answer","ts":2.0}
        ]}"""
        val messages = parseHistory(body)
        assertEquals(2, messages.size)
        assertEquals("you", messages[0].role)
        assertNull(messages[0].thinking)
        assertEquals("forge", messages[1].role)
        // v1.6.3 (JAG-55): the reply is the main text; `reasoning` lands in the
        // CoT drawer field, never as the primary bubble text.
        assertEquals("ok", messages[1].text)
        assertEquals("We need to answer", messages[1].thinking)
    }

    @Test
    fun splitsThinkTailOutOfReply() {
        assertEquals("reply text" to "hidden chain", splitAnswerTail("reply text\nthink: hidden chain"))
        assertEquals("plain reply" to "", splitAnswerTail("plain reply"))
        assertEquals("" to "only thinking", splitAnswerTail("think: only thinking"))
    }

    @Test
    fun mapsInlineToolCards() {
        // v1.6.3 (JAG-55): tool.call → pending card, tool.result → outcome card.
        val pending = toolCardFromCall(org.json.JSONObject("""{"tool":"write_todos"}"""))
        assertEquals("write_todos", pending.tool)
        assertNull(pending.ok)
        val done = toolCardFromResult(
            org.json.JSONObject(
                """{"tool":"write_todos","ok":true,"exit_code":0,
                    "backend":"harness","summary":"3 nodi nel task graph"}"""
            )
        )
        assertEquals("write_todos", done.tool)
        assertEquals(true, done.ok)
        assertEquals(0, done.exitCode)
        assertEquals("harness", done.backend)
        assertEquals("3 nodi nel task graph", done.summary)
    }

    @Test
    fun toolCardLandsBeforeTheReplyAndKeepsStreamingIndex() {
        // v1.6.3 (JAG-55): tool.call arrives before the reply deltas → the card
        // must sit between the user turn and the streaming bubble.
        val msgs = listOf(
            ForgeMessage("you", "domanda"),
            ForgeMessage("forge", "", streaming = true)
        )
        val (after, index) = insertToolCard(
            msgs, 1, toolCardFromCall(org.json.JSONObject("""{"tool":"write_todos"}"""))
        )
        assertEquals(listOf("you", "tool", "forge"), after.map { it.role })
        assertEquals(2, index) // streaming bubble shifted: deltas keep landing on it
        assertEquals("write_todos", after[1].tool?.tool)
        assertNull(after[1].tool?.ok)

        val done = applyToolResult(
            after,
            toolCardFromResult(
                org.json.JSONObject("""{"tool":"write_todos","ok":true,"exit_code":0}""")
            )
        )
        assertEquals(3, done.size) // result fills the pending card, no duplicate
        assertEquals(true, done[1].tool?.ok)
        assertEquals(0, done[1].tool?.exitCode)
    }

    @Test
    fun toolResultWithoutCallStillRendersACard() {
        // agent runs may emit tool.result for a tool whose call came earlier
        val out = applyToolResult(
            listOf(ForgeMessage("you", "x")),
            toolCardFromResult(org.json.JSONObject("""{"tool":"fs.edit","ok":false}"""))
        )
        assertEquals(2, out.size)
        assertEquals("fs.edit", out[1].tool?.tool)
        assertEquals(false, out[1].tool?.ok)
    }

    @Test
    fun toolCardCapturesInputOutputAndErrorForExpansion() {
        // v1.6.9 (JAG-58d): the inline card is expandable → it must carry the
        // tool input (args), output (stdout) and error (stderr).
        val call = toolCardFromCall(
            org.json.JSONObject("""{"tool":"shell","args":{"command":"mkdir foo"}}""")
        )
        assertEquals("shell", call.tool)
        assertTrue(call.args.contains("mkdir foo"))

        val done = toolCardFromResult(
            org.json.JSONObject(
                """{"tool":"shell","ok":false,"exit_code":1,
                    "stdout":"partial out","stderr":"permission denied"}"""
            )
        )
        assertEquals("partial out", done.result)
        assertEquals("permission denied", done.error)
    }

    @Test
    fun applyingResultKeepsTheCapturedInput() {
        // v1.6.9 (JAG-58d): tool.result carries no args — the pending card's
        // input must survive the merge so the expanded inspector stays complete.
        val msgs = listOf(
            ForgeMessage("you", "q"),
            ForgeMessage(
                "tool", "",
                tool = toolCardFromCall(
                    org.json.JSONObject("""{"tool":"shell","args":{"command":"ls"}}""")
                )
            ),
            ForgeMessage("forge", "", streaming = true)
        )
        val out = applyToolResult(
            msgs,
            toolCardFromResult(
                org.json.JSONObject("""{"tool":"shell","ok":true,"stdout":"a b c"}""")
            )
        )
        assertEquals(3, out.size)
        assertTrue(out[1].tool!!.args.contains("ls"))
        assertEquals("a b c", out[1].tool!!.result)
    }

    @Test
    fun handlesMissingArraysGracefully() {
        assertTrue(parseSessions("{}").isEmpty())
        assertTrue(parseTasks("not json").isEmpty())
        assertTrue(parseHistory("{}").isEmpty())
        assertTrue(parseTaskArray(null).isEmpty())
    }
}
