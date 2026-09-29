package com.jagones.sparkpulse

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeSelfcheckTest {

    // Trimmed copy of a real `GET /api/selfcheck` payload from the DGX harness.
    private val healthy = """
        {"service":"sparkforge","version":"0.5.1","status":"ok",
         "model_requested":"nex-n25-mini-uncensored-q8","model_loaded":true,
         "model_loaded_alias":"nex-n25-mini-uncensored-q8",
         "router_reachable":true,"router_latency_ms":0.7,
         "llm_latency_ms":126.7,"token_configured":true,"auth_required":true}
    """.trimIndent()

    @Test
    fun `healthy selfcheck is green and summarises the harness`() {
        val out = parseSelfCheck(healthy)
        assertTrue(out.ok)
        assertEquals("Connessione OK", out.title)
        assertTrue(out.detail.contains("0.5.1"))
        assertTrue(out.detail.contains("nex-n25-mini-uncensored-q8"))
        assertTrue(out.detail.contains("router ok"))
        assertTrue(out.detail.contains("auth richiesta"))
    }

    @Test
    fun `degraded selfcheck is red with its status`() {
        val out = parseSelfCheck("""{"status":"degraded","version":"0.5.1"}""")
        assertFalse(out.ok)
        assertTrue(out.title.contains("degraded"))
    }

    @Test
    fun `non json body is reported instead of crashing`() {
        val out = parseSelfCheck("<html>502 Bad Gateway</html>")
        assertFalse(out.ok)
        assertTrue(out.title.contains("non JSON"))
    }

    @Test
    fun `401 is reported as a token problem`() {
        val msg = describeNetworkFailure("100.102.61.23", IllegalStateException("HTTP 401"))
        assertTrue(msg.contains("401"))
        assertTrue(msg.contains("Token"))
    }

    @Test
    fun `timeout names the failure and the endpoint`() {
        val msg = describeNetworkFailure("100.102.61.23", SocketTimeoutException("timed out"))
        assertTrue(msg.contains("Timeout"))
        assertTrue(msg.contains("100.102.61.23:$FORGE_PORT"))
    }

    @Test
    fun `dns failure hints at the tailscale ip`() {
        val msg = describeNetworkFailure("dgx.local", UnknownHostException("dgx.local"))
        assertTrue(msg.contains("DNS"))
        assertTrue(msg.contains("Tailscale"))
    }

    @Test
    fun `refused connection names the port`() {
        val msg = describeNetworkFailure("100.102.61.23", ConnectException("Connection refused"))
        assertTrue(msg.contains("rifiutata"))
        assertTrue(msg.contains("$FORGE_PORT"))
    }

    @Test
    fun `failure outcome carries an explicit title and hint`() {
        val out = selfCheckFailure("100.102.61.23", IllegalStateException("HTTP 401"))
        assertFalse(out.ok)
        assertEquals("Token non valido (HTTP 401)", out.title)
        assertTrue(out.detail.contains("Token"))
    }

    @Test
    fun `blank host is reported as invalid instead of dialling`() {
        val out = SelfCheckClient().test("   ", "token")
        assertFalse(out.ok)
        assertEquals("Host non valido", out.title)
    }

    @Test
    fun `http code helper only matches real status codes`() {
        assertEquals("404", httpCodeFrom("HTTP 404: not found"))
        assertNull(httpCodeFrom("token non valido"))
        assertNull(httpCodeFrom(null))
    }
}
