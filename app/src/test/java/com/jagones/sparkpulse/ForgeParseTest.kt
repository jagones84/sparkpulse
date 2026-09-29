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
        assertEquals("We need to answer", messages[1].thinking)
    }

    @Test
    fun handlesMissingArraysGracefully() {
        assertTrue(parseSessions("{}").isEmpty())
        assertTrue(parseTasks("not json").isEmpty())
        assertTrue(parseHistory("{}").isEmpty())
        assertTrue(parseTaskArray(null).isEmpty())
    }
}
