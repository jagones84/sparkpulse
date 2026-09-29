package com.jagones.sparkpulse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6: parsers backing the v0.6 per-run task-graph panel. */
class ForgeGraphTest {

    @Test
    fun parsesRunGraphWithDepsAndEvidence() {
        val body = """{
            "run_id":"beefef36ae98","session_id":"v06-live","goal":"analizza il README",
            "node_count":3,"status":"done","counts":{"todo":0,"done":3},
            "nodes":[
              {"id":"n1","label":"Analizza il file","status":"done","deps":[],
               "evidence":[{"ts":1.0,"text":"cmd=ls output=README.md exit=0"}]},
              {"id":"n2","label":"Estrai 3 punti","status":"todo","deps":["n1"],"evidence":[]}
            ]}"""
        val (runId, nodes) = parseGraph(body)
        assertEquals("beefef36ae98", runId)
        assertEquals(2, nodes.size)
        assertEquals("n2", nodes[1].id)
        assertEquals(listOf("n1"), nodes[1].deps)
        assertEquals("cmd=ls output=README.md exit=0", nodes[0].evidence.single())
        assertTrue(nodes[1].evidence.isEmpty())
    }

    @Test
    fun parsesGraphNodeFromSsePayload() {
        val obj = JSONObject(
            """{"id":"n3","label":"Sintesi finale","status":"doing","deps":["n1","n2"],"evidence":["x"]}"""
        )
        val node = parseGraphNode(obj)
        assertEquals("n3", node?.id)
        assertEquals("doing", node?.status)
        assertEquals(listOf("n1", "n2"), node?.deps)
        assertEquals(listOf("x"), node?.evidence)
    }

    @Test
    fun defaultsStatusAndToleratesMissingFields() {
        val node = parseGraphNode(JSONObject("""{"id":"n9","label":"x"}"""))
        assertEquals("todo", node?.status)
        assertTrue(node!!.deps.isEmpty())
        assertTrue(node.evidence.isEmpty())
        assertNull(parseGraphNode(null))
        assertNull(parseGraphNode(JSONObject("{}")))     // no id → not a node
        assertNull(parseGraphNode(runCatching { JSONObject("not json") }.getOrNull()))
    }

    @Test
    fun handlesBadGraphPayload() {
        val (runId, nodes) = parseGraph("not json")
        assertEquals("", runId)
        assertTrue(nodes.isEmpty())
        assertTrue(parseGraph("""{"run_id":"r1"}""").second.isEmpty())
    }
}
