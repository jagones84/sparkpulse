package com.jagones.sparkpulse

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import org.json.JSONObject

/** SparkForge health probe (harness v0.5.1): `GET /api/selfcheck`. */
internal const val SELFCHECK_PATH = "/api/selfcheck"

/** The probe is local: it must answer quickly or the host/token are wrong. */
internal const val SELFCHECK_TIMEOUT_MS = 15_000

private val HTTP_CODE = Regex("HTTP (\\d{3})")

/** Extracts the status code from an `HTTP <code>` failure message, if any. */
internal fun httpCodeFrom(message: String?): String? =
    HTTP_CODE.find(message.orEmpty())?.groupValues?.get(1)

/** Outcome of a connection probe, ready to be rendered (green/red) in the UI. */
data class SelfCheckOutcome(val ok: Boolean, val title: String, val detail: String)

/**
 * Turns a low-level failure into an explicit, actionable message. The cold-start
 * cases the app must name are 401/403 (token), timeout and DNS — instead of the
 * old silent "chat morta".
 */
internal fun describeNetworkFailure(host: String, error: Throwable): String {
    val base = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        .ifEmpty { host.trim() }
    val where = if (base.isNotEmpty()) "$base:$FORGE_PORT" else "host:$FORGE_PORT"
    return when (error) {
        is UnknownHostException -> "Host non risolto (DNS): «$base». Usa l'IP Tailscale del DGX."
        is SocketTimeoutException -> "Timeout su «$where»: DGX spento o LLM in avvio oltre il limite."
        is ConnectException -> "Connessione rifiutata su «$where»: SparkForge in ascolto? host/porta corretti?"
        else -> when (val code = httpCodeFrom(error.message)) {
            "401", "403" -> "Token non valido (HTTP $code): riallinea il token nelle Impostazioni FORGE."
            "404" -> "Endpoint non trovato (HTTP 404): host raggiunto ma SparkForge senza questa API."
            null -> "Errore di rete: " + (error.message ?: error.javaClass.simpleName).take(140)
            else -> "Errore HTTP $code: " + (error.message ?: "").take(140)
        }
    }
}

/** Parses a `GET /api/selfcheck` body into a readable, testable outcome. */
internal fun parseSelfCheck(body: String): SelfCheckOutcome {
    val j = runCatching { JSONObject(body) }.getOrNull()
        ?: return SelfCheckOutcome(false, "Risposta non JSON", body.trim().take(160))
    val status = j.optString("status", "").ifEmpty { "?" }
    val version = j.optString("version", "").ifEmpty { "?" }
    val model = j.optString("model_loaded_alias", "").ifEmpty { j.optString("model_requested", "") }
    val detail = buildString {
        append("SparkForge ").append(version).append(" · ").append(status)
        if (model.isNotEmpty()) append(" · modello ").append(model)
        if (j.has("router_reachable")) {
            append(" · router ").append(if (j.optBoolean("router_reachable")) "ok" else "KO")
        }
        append(" · ").append(if (j.optBoolean("auth_required")) "auth richiesta" else "auth libera")
        val latency = j.optDouble("llm_latency_ms", -1.0)
        if (latency >= 0) append(" · LLM ").append(latency.toInt()).append(" ms")
    }
    val ok = status == "ok"
    return SelfCheckOutcome(ok, if (ok) "Connessione OK" else "Stato «$status»", detail)
}

/** Maps a thrown failure to a red outcome with an explicit title + hint. */
internal fun selfCheckFailure(host: String, error: Throwable): SelfCheckOutcome {
    val code = httpCodeFrom(error.message)
    val title = when (error) {
        is UnknownHostException -> "Host non risolto (DNS)"
        is SocketTimeoutException -> "Timeout"
        is ConnectException -> "Connessione rifiutata"
        else -> if (code == "401" || code == "403") "Token non valido (HTTP $code)" else "Errore di rete"
    }
    return SelfCheckOutcome(false, title, describeNetworkFailure(host, error))
}

/**
 * Minimal `GET /api/selfcheck` client used by the settings-panel "Test
 * connessione" button and by the "Dove sei" quick action. It reads the error
 * body so a 401 is reported as a token problem rather than a generic failure.
 */
class SelfCheckClient {
    fun test(host: String, token: String): SelfCheckOutcome {
        val base = try {
            normalizeForgeHost(host)
        } catch (e: IllegalArgumentException) {
            return SelfCheckOutcome(false, "Host non valido", e.message ?: "host vuoto")
        }
        val bearer = token.trim()
        val url = StringBuilder("http://$base:$FORGE_PORT$SELFCHECK_PATH")
        if (bearer.isNotEmpty()) url.append("?token=").append(URLEncoder.encode(bearer, "UTF-8"))
        val conn = try {
            URL(url.toString()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return selfCheckFailure(base, e)
        }
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = SELFCHECK_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (bearer.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val code = conn.responseCode
            when {
                code in 200..299 ->
                    parseSelfCheck(conn.inputStream.bufferedReader().use { it.readText() })
                code == 401 || code == 403 -> SelfCheckOutcome(
                    false, "Token non valido (HTTP $code)",
                    "SparkForge rifiuta il token inviato: riallinealo nelle Impostazioni FORGE."
                )
                else -> SelfCheckOutcome(
                    false, "Risposta inattesa (HTTP $code)",
                    runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }
                        .getOrDefault("").trim().take(160)
                )
            }
        } catch (e: Exception) {
            selfCheckFailure(base, e)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
