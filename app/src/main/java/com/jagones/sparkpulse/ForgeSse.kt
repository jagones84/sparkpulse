package com.jagones.sparkpulse

import android.content.Context
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** SparkForge harness port (systemd unit on the DGX Spark, listening on :8790). */
internal const val FORGE_PORT = 8790

/**
 * Client deadline floor for a cold-start run (v1.5.1). The first token can take
 * as long as SparkForge's warm-up (`SPARKFORGE_MODEL_LOAD_TIMEOUT`, default
 * 900 s), so the client must never abort a chat/agent run below this: the
 * requirement is >= 120 s.
 */
internal const val FORGE_MIN_RUN_TIMEOUT_MS = 120_000

/** Non-SSE REST deadline (sessions/tasks/history): comfortably above the floor. */
internal const val FORGE_REST_READ_TIMEOUT_MS = 300_000

/**
 * SSE read deadline. Zero means unbounded: the socket relies on server
 * heartbeats and on the `model.loading` event, so a cold model can warm up for
 * minutes without being cut off (unbounded is always >= [FORGE_MIN_RUN_TIMEOUT_MS]).
 */
internal const val FORGE_SSE_READ_TIMEOUT_MS = 0

/**
 * Terminal SSE event (v1.6.1, JAG-49). SparkForge closes every chat/agent
 * stream with exactly one `done`: that event *is* the end of the exchange, even
 * when the socket outlives it (v0.6.1 shuts the write side down, older builds
 * and stalled upstreams did not). The client must therefore stop on `done`
 * instead of waiting for EOF.
 */
internal const val TERMINAL_SSE_EVENT = "done"

/** True when [type] marks the end of a stream. See [TERMINAL_SSE_EVENT]. */
internal fun isTerminalSseEvent(type: String) = type == TERMINAL_SSE_EVENT

/** Strips scheme/trailing slash and validates the configured SparkForge host. */
internal fun normalizeForgeHost(host: String): String {
    val normalized = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    require(normalized.isNotBlank()) { "Host SparkForge vuoto" }
    return normalized
}

/** One parsed Server-Sent Event block. */
data class SseEvent(val id: String?, val type: String, val data: String) {
    fun json(): JSONObject? = runCatching { JSONObject(data) }.getOrNull()
}

/**
 * Incremental parser for the `text/event-stream` framing: `id:`, `event:` and
 * `data:` fields accumulate until a blank separator line completes the block.
 * Comment lines (`: ping`) are heartbeats and are ignored.
 *
 * Kept Android-free so it can be unit tested with plain strings.
 */
class SseLineParser {
    private var id: String? = null
    private var type = "message"
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty()) {
                reset()
                return null
            }
            val event = SseEvent(id, type, data.toString().trimEnd('\n'))
            reset()
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> id = value
            "event" -> type = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    private fun reset() {
        id = null
        type = "message"
        data.setLength(0)
    }
}

/**
 * Minimal SSE client over [HttpURLConnection] — no external libraries.
 * The token is sent both as `Authorization: Bearer` and as the `?token=`
 * query fallback the harness accepts for header-less EventSource clients.
 */
class SseClient {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false

    /** Interrupts the blocking read by dropping the socket. */
    fun close() {
        closed = true
        runCatching { connection?.disconnect() }
    }

    fun stream(
        host: String,
        path: String,
        token: String?,
        onOpen: () -> Unit = {},
        onEvent: (SseEvent) -> Unit
    ) {
        val base = normalizeForgeHost(host)
        val bearer = token?.trim().orEmpty()
        val separator = if (path.contains('?')) '&' else '?'
        val builder = StringBuilder("http://$base:$FORGE_PORT$path")
        if (bearer.isNotEmpty()) {
            builder.append(separator).append("token=").append(URLEncoder.encode(bearer, "UTF-8"))
        }
        val conn = URL(builder.toString()).openConnection() as HttpURLConnection
        // v1.6.1 (JAG-49): a previous Stop() flags this client closed. Every new
        // stream must start clean, otherwise the read loop would exit at once
        // and the next message after a Stop would never be answered.
        closed = false
        connection = conn
        conn.connectTimeout = 8_000
        conn.readTimeout = FORGE_SSE_READ_TIMEOUT_MS // SSE: heartbeats + model.loading, unbounded
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-store")
        if (bearer.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            onOpen()
            conn.inputStream.bufferedReader().use { reader -> readLoop(reader, onEvent) }
        } finally {
            runCatching { conn.disconnect() }
            connection = null
        }
    }

    private fun readLoop(reader: BufferedReader, onEvent: (SseEvent) -> Unit) {
        val parser = SseLineParser()
        while (!closed) {
            val line = reader.readLine() ?: break
            parser.feed(line)?.let(onEvent)
        }
    }
}

/** Plain request/response helper for the non-SSE SparkForge endpoints. */
class ForgeRest {
    fun text(
        host: String,
        token: String,
        path: String,
        method: String = "GET",
        jsonBody: String? = null,
        rawBody: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8"
    ): String {
        val base = normalizeForgeHost(host)
        val bearer = token.trim()
        val separator = if (path.contains('?')) '&' else '?'
        val url = StringBuilder("http://$base:$FORGE_PORT$path")
        if (bearer.isNotEmpty()) url.append(separator).append("token=").append(URLEncoder.encode(bearer, "UTF-8"))
        val conn = URL(url.toString()).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = FORGE_REST_READ_TIMEOUT_MS
            conn.requestMethod = method
            if (bearer.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val payload = rawBody ?: jsonBody?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(payload) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Newest durable feed id, so a live tail starts from "now" instead of replaying all. */
    fun latestFeedId(host: String, token: String): Long {
        val body = text(host, token, "/api/feed/recent?limit=1")
        val events = JSONObject(body).optJSONArray("events") ?: return 0L
        if (events.length() == 0) return 0L
        return events.optJSONObject(events.length() - 1)?.optLong("id", 0L) ?: 0L
    }
}

/** Token/host persisted by the app (v1.3 config surface extended in v1.4). */
object ForgeConfig {
    private const val PREFS = "sparkpulse_forge"
    private const val KEY_HOST = "forge_host"
    private const val KEY_TOKEN = "forge_token"

    /** v1.6.4 (JAG-57): last selected Forge session id, restored on cold start. */
    private const val KEY_SESSION = "forge_session"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun host(context: Context): String =
        prefs(context).getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

    /** Reuses the token already configured for the app; build default comes from a
     *  gitignored gradle property so the secret never lands in the repository. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null) ?: BuildConfig.FORGE_TOKEN

    fun save(context: Context, host: String, token: String) {
        prefs(context).edit().putString(KEY_HOST, host.trim()).putString(KEY_TOKEN, token.trim()).apply()
    }

    /** v1.6.4 (JAG-57): persists the selected session like host/token. */
    fun saveSession(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_SESSION, id?.takeIf { it.isNotBlank() }).apply()
    }

    fun session(context: Context): String? =
        prefs(context).getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }
}
