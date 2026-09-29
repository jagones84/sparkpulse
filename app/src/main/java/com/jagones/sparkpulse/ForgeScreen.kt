package com.jagones.sparkpulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal val FInk = Color(0xFF0A0E14)
internal val FPanel = Color(0xFF141B24)
internal val FPanelRaised = Color(0xFF1B2531)
internal val FTextMain = Color(0xFFF0F4F8)
internal val FTextMuted = Color(0xFF9AA8B7)
internal val FMint = Color(0xFF57E3B1)
internal val FBlue = Color(0xFF8DB8FF)
internal val FAmber = Color(0xFFFFC66D)
internal val FCoral = Color(0xFFFF7777)
internal val FViolet = Color(0xFFB98BFF)

/** Quick-action "Dove sei / comandi": goal that makes the agent invoke the v0.5 `self` tool. */
internal const val SELF_GOAL =
    "Chiama il tool self e riporta dove sei: percorsi repo/data/sessioni, config, docs, " +
    "stato del servizio SparkForge e come aggiungere skill o server MCP."

/** One chat exchange kept in the Forge transcript. */
data class ForgeMessage(
    val role: String, // "you" | "forge" | "system"
    val text: String,
    val thinking: String? = null,
    val streaming: Boolean = false
)

/** One persisted SparkForge session, as returned by `GET /api/sessions` (v0.5). */
data class ForgeSession(val id: String, val title: String, val messages: Int, val created: Double)

/** One todo item from the live task breakdown feed (`tasks.update` / `/api/tasks`). */
data class ForgeTask(val id: String, val title: String, val status: String)

/** Parses the `{"sessions":[...]}` payload of `GET /api/sessions`. */
internal fun parseSessions(body: String): List<ForgeSession> {
    val arr = runCatching { JSONObject(body).optJSONArray("sessions") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        ForgeSession(o.optString("id"), o.optString("title"), o.optInt("messages"), o.optDouble("created", 0.0))
    }
}

/** Parses the `{"tasks":[...]}` payload of `GET /api/tasks`. */
internal fun parseTasks(body: String): List<ForgeTask> =
    parseTaskArray(runCatching { JSONObject(body).optJSONArray("tasks") }.getOrNull())

/** Parses a `tasks` array (from `/api/tasks` or a `tasks.update` SSE event). */
internal fun parseTaskArray(arr: JSONArray?): List<ForgeTask> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        ForgeTask(o.optString("id"), o.optString("title"), o.optString("status", "todo"))
    }
}

/**
 * One node of the LLM-generated run task graph (SparkForge v0.6). The graph is
 * bound to a run (`graph.node.added` / `graph.node.updated` over SSE) and every
 * `done` node carries evidence — which the detail view shows on tap.
 */
data class ForgeNode(
    val id: String,
    val label: String,
    val status: String,            // todo | doing | done | blocked | cancelled
    val deps: List<String>,
    val evidence: List<String>
)

/** Parses a single graph node (from `/api/runs/<id>/graph` or a graph.node.* event). */
internal fun parseGraphNode(o: JSONObject?): ForgeNode? {
    if (o == null) return null
    val id = o.optString("id")
    if (id.isEmpty()) return null
    val deps = o.optJSONArray("deps")?.let { arr ->
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    } ?: emptyList()
    val evidence = o.optJSONArray("evidence")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.opt(i)) {
                is JSONObject -> e.optString("text")
                is String -> e
                else -> null
            }
        }.filter { it.isNotBlank() }
    } ?: emptyList()
    return ForgeNode(id, o.optString("label"), o.optString("status", "todo"), deps, evidence)
}

/** Parses `GET /api/runs/<id>/graph` → (run_id, nodes). */
internal fun parseGraph(body: String): Pair<String, List<ForgeNode>> {
    val j = runCatching { JSONObject(body) }.getOrNull() ?: return "" to emptyList()
    val runId = j.optString("run_id")
    val arr = j.optJSONArray("nodes") ?: return runId to emptyList()
    val nodes = (0 until arr.length()).mapNotNull { parseGraphNode(arr.optJSONObject(it)) }
    return runId to nodes
}

/**
 * Builds the JSON body for `POST /api/runs/<id>/graph/nodes` (SparkForge v0.6).
 * Actions: `add` | `cancel` | `complete` | `update` | `replan`. Kept pure so it
 * can be unit-tested without a live server. Blank fields are omitted, matching
 * the server's `body.get(...)`-with-default handler.
 */
internal fun graphActionBody(
    action: String,
    id: String? = null,
    label: String? = null,
    note: String? = null,
    source: String = "operator"
): JSONObject = JSONObject()
    .put("action", action)
    .put("source", source)
    .also { o -> id?.takeIf { it.isNotBlank() }?.let { o.put("id", it) } }
    .also { o -> label?.takeIf { it.isNotBlank() }?.let { o.put("label", it) } }
    .also { o -> note?.takeIf { it.isNotBlank() }?.let { o.put("note", it) } }

/** Rebuilds the chat transcript from the `messages` array of `GET /api/history`. */
internal fun parseHistory(body: String): List<ForgeMessage> {
    val arr = runCatching { JSONObject(body).optJSONArray("messages") }.getOrNull() ?: return emptyList()
    val out = mutableListOf<ForgeMessage>()
    for (i in 0 until arr.length()) {
        val m = arr.optJSONObject(i) ?: continue
        val role = when (m.optString("role")) {
            "user" -> "you"
            "assistant" -> "forge"
            else -> m.optString("role").ifEmpty { "system" }
        }
        out += ForgeMessage(role, m.optString("content"), m.optString("reasoning").ifEmpty { null })
    }
    return out
}

/**
 * SSE-driven Forge/chat state. Both the chat stream and the agent loop push into
 * a channel consumed on the main dispatcher, so UI deltas arrive ordered and
 * thread-safe while the blocking socket read happens on IO.
 */
class ForgeViewModel : ViewModel() {
    private val client = SseClient()
    private var streamJob: Job? = null

    var messages by mutableStateOf(
        listOf(
            ForgeMessage(
                "system",
                "SSE nativo attivo su SparkForge :$FORGE_PORT — chat, agente e feed in streaming."
            )
        )
    )
        private set
    var agentLines by mutableStateOf(listOf<String>())
        private set
    var liveThinking by mutableStateOf("")
        private set
    var banner by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var sessionId: String? = null
    private var streamingIndex: Int? = null

    // ── v0.5 additions (extended, not rewritten) ─────────────────────
    private val rest = ForgeRest()
    private var boundHost = ""
    private var boundToken = ""
    private var tasksSse: SseClient? = null
    private var tasksJob: Job? = null

    /** Session list for the switch/create/delete panel. */
    var sessions by mutableStateOf(listOf<ForgeSession>())
        private set

    /** Id of the session currently loaded into the transcript. */
    var activeSession by mutableStateOf<String?>(null)
        private set

    /** CoT drawer content: accumulated `think` channel of the chat stream. */
    var reasoning by mutableStateOf("")
        private set
    var cotOpen by mutableStateOf(false)
        private set

    /** Token indicator fed by `GET /api/context`. */
    var contextUsed by mutableStateOf(0)
        private set
    var contextBudget by mutableStateOf(0)
        private set
    var contextOver by mutableStateOf(false)
        private set

    /** Live todo breakdown, seeded from `GET /api/tasks` and updated by SSE. */
    var tasks by mutableStateOf(listOf<ForgeTask>())
        private set

    // ── v0.6: per-run LLM task graph (live, tap a node for evidence) ──
    var graphNodes by mutableStateOf(listOf<ForgeNode>())
        private set
    var graphRunId by mutableStateOf<String?>(null)
        private set
    var graphGoal by mutableStateOf("")
        private set
    var graphOpen by mutableStateOf(false)
        private set
    var selectedNode by mutableStateOf<ForgeNode?>(null)
        private set

    /** v0.6 interactivity: text of the "new node" field in the graph panel. */
    var graphDraft by mutableStateOf("")
        private set

    /** Transient panel feedback (session created/deleted, compaction stats…). */
    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** Cold-start banner: set by `model.loading`, cleared by `model.ready`. */
    var coldStart by mutableStateOf<String?>(null)
        private set

    /** Outcome of the last "Test connessione" / `/api/selfcheck` probe. */
    var selfCheck by mutableStateOf<SelfCheckOutcome?>(null)
        private set
    var selfChecking by mutableStateOf(false)
        private set

    fun sendChat(host: String, token: String, text: String) {
        if (text.isBlank() || busy) return
        val query = buildString {
            append("/api/chat/stream?message=").append(URLEncoder.encode(text, "UTF-8"))
            sessionId?.let { append("&session=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        graphGoal = text
        banner = "pianifico…"
        messages = messages + ForgeMessage("you", text) + ForgeMessage("forge", "", streaming = true)
        streamingIndex = messages.lastIndex
        busy = true
        error = null
        coldStart = null
        reasoning = ""
        streamJob = launchStream(host, token, query,
            onOpen = { },
            onEvent = ::handleChatEvent,
            onEnd = { failure ->
                failure?.let { error = "Chat interrotta: ${it.take(140)}" }
                finishStreaming()
            }
        )
    }

    fun runAgent(host: String, token: String, goal: String) {
        if (goal.isBlank() || busy) return
        agentLines = listOf("▶ obiettivo: ${goal.take(160)}")
        liveThinking = ""
        busy = true
        error = null
        coldStart = null
        reasoning = ""
        graphGoal = goal
        banner = "pianifico…"
        val query = "/api/agent/run?goal=" + URLEncoder.encode(goal, "UTF-8") + "&max_steps=6"
        streamJob = launchStream(host, token, query,
            onOpen = { },
            onEvent = ::handleAgentEvent,
            onEnd = { failure ->
                failure?.let { error = "Agente interrotto: ${it.take(140)}" }
                busy = false
                liveThinking = ""
            }
        )
    }

    /** Drops the socket and cancels the current SSE stream (chat or agent). */
    fun stop() {
        client.close()
        streamJob?.cancel()
        streamJob = null
        finishStreaming()
        busy = false
        liveThinking = ""
    }

    // ── v0.5: sessions, context/compaction, tasks feed, self-knowledge ──

    /** Binds the panel endpoints to the configured host/token (idempotent). */
    fun bind(host: String, token: String) {
        if (boundHost == host && boundToken == token) return
        boundHost = host
        boundToken = token
        refreshSessions()
        refreshContext()
        refreshTasks()
        startTaskFeed(host, token)
    }

    fun toggleCot() {
        cotOpen = !cotOpen
    }

    // ── v0.6: task graph ──

    /** Opens/closes the graph panel and re-syncs it from the server. */
    fun toggleGraph() {
        graphOpen = !graphOpen
        graphRunId?.let { refreshGraph(it) }
    }

    /** Tap-to-detail: selecting the same node again closes the detail. */
    fun selectNode(node: ForgeNode?) {
        selectedNode = if (node != null && selectedNode?.id == node.id) null else node
    }

    /** `GET /api/runs/<id>/graph` — full graph for the current run. */
    fun refreshGraph(runId: String) {
        val host = boundHost
        val token = boundToken
        if (host.isBlank() || runId.isBlank()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { rest.text(host, token, "/api/runs/" + URLEncoder.encode(runId, "UTF-8") + "/graph") }
            }
            result.onSuccess { body ->
                val (rid, nodes) = parseGraph(body)
                if (rid.isNotEmpty() || nodes.isNotEmpty()) {
                    graphRunId = rid.ifEmpty { runId }
                    graphNodes = nodes
                    selectedNode = selectedNode?.let { s -> nodes.firstOrNull { it.id == s.id } }
                }
            }
        }
    }

    /** Upserts a node pushed live via `graph.node.added` / `graph.node.updated`. */
    private fun upsertNode(runId: String, node: ForgeNode) {
        val rid = runId.ifEmpty { graphRunId ?: "" }
        if (graphRunId != null && rid.isNotEmpty() && graphRunId != rid) {
            graphRunId = rid
            graphNodes = listOf(node)
            selectedNode = null
            return
        }
        graphRunId = rid.ifEmpty { graphRunId }
        val index = graphNodes.indexOfFirst { it.id == node.id }
        graphNodes = if (index >= 0) {
            graphNodes.toMutableList().also {
                it[index] = node
                selectedNode = selectedNode?.let { s -> if (s.id == node.id) node else s }
            }
        } else {
            graphNodes + node
        }
    }

    // ── v0.6 interactivity: mutate the run graph (add / cancel / re-plan) ──

    /** Updates the "new node" field text of the graph panel. */
    fun setGraphDraft(text: String) {
        graphDraft = text
    }

    /** Adds an operator node to the current run's graph. */
    fun addGraphNode() {
        val label = graphDraft.trim()
        if (label.isEmpty()) return
        graphDraft = ""
        postGraphAction("add", label = label)
    }

    /** Cancels a node of the current run's graph. */
    fun cancelGraphNode(node: ForgeNode) {
        postGraphAction("cancel", id = node.id)
    }

    /** Asks the server to re-plan the current run (incremental nodes, in place). */
    fun replanGraph() {
        postGraphAction("replan", note = graphGoal.ifBlank { null })
    }

    /**
     * `POST /api/runs/<id>/graph/nodes` — one mutation against the *current run's*
     * graph (add | cancel | replan). Live `graph.node.*` SSE events then update the
     * list on their own; the explicit refresh covers servers without the feed.
     */
    private fun postGraphAction(action: String, id: String? = null, label: String? = null, note: String? = null) {
        val runId = graphRunId
        val host = boundHost
        val token = boundToken
        if (runId.isNullOrBlank() || host.isBlank()) {
            statusMessage = "Nessun run attivo: invia prima una richiesta a SparkForge."
            return
        }
        val body = graphActionBody(action, id = id, label = label, note = note).toString()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(
                        host, token,
                        "/api/runs/" + URLEncoder.encode(runId, "UTF-8") + "/graph/nodes",
                        "POST", jsonBody = body
                    )
                }
            }
            result.onSuccess {
                statusMessage = "Grafo · $action inviato"
                refreshGraph(runId)
            }.onFailure {
                statusMessage = "Grafo · $action fallito: ${it.message?.take(120)}"
            }
        }
    }

    /** `GET /api/sessions` — list/switch/create/delete source. */
    fun refreshSessions() {
        val host = boundHost
        val token = boundToken
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { parseSessions(rest.text(host, token, "/api/sessions")) }.getOrDefault(emptyList())
            }
            sessions = list
        }
    }

    /** `POST /api/sessions` — starts a fresh session and loads it. */
    fun newSession(title: String) {
        val host = boundHost
        val token = boundToken
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(
                        host, token, "/api/sessions", "POST",
                        jsonBody = JSONObject().put("title", title.ifBlank { "mobile" }).toString()
                    )
                }
            }
            result.onSuccess { body ->
                val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("")
                if (id.isNotEmpty()) {
                    sessionId = id
                    activeSession = id
                    messages = listOf(ForgeMessage("system", "Nuova sessione $id su SparkForge :$FORGE_PORT."))
                    reasoning = ""
                    statusMessage = "Sessione creata: $id"
                }
            }.onFailure { statusMessage = "Errore creazione: ${it.message?.take(120)}" }
            refreshSessions()
            refreshContext()
        }
    }

    /** `GET /api/history?session=<id>` — loads a session transcript into the view. */
    fun switchSession(id: String) {
        val host = boundHost
        val token = boundToken
        sessionId = id
        activeSession = id
        reasoning = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { rest.text(host, token, "/api/history?session=" + URLEncoder.encode(id, "UTF-8")) }
            }
            result.onSuccess { body ->
                messages = parseHistory(body).ifEmpty {
                    listOf(ForgeMessage("system", "Sessione $id vuota."))
                }
                statusMessage = "Sessione attiva: $id"
            }.onFailure { statusMessage = "Errore history: ${it.message?.take(120)}" }
            refreshContext()
        }
    }

    /** `DELETE /api/sessions/<id>` — removes the session and clears it if active. */
    fun deleteSession(id: String) {
        val host = boundHost
        val token = boundToken
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { rest.text(host, token, "/api/sessions/" + URLEncoder.encode(id, "UTF-8"), "DELETE") }
            }
            if (activeSession == id) {
                sessionId = null
                activeSession = null
                messages = listOf(ForgeMessage("system", "Sessione eliminata. Chat senza sessione."))
            }
            statusMessage = "Sessione eliminata: $id"
            refreshSessions()
        }
    }

    /** `GET /api/context` — token usage vs budget for the active session. */
    fun refreshContext() {
        val host = boundHost
        val token = boundToken
        val sid = sessionId
        viewModelScope.launch {
            val path = "/api/context" + (sid?.let { "?session=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val result = withContext(Dispatchers.IO) { runCatching { rest.text(host, token, path) } }
            result.onSuccess { body ->
                val j = runCatching { JSONObject(body) }.getOrNull() ?: return@onSuccess
                contextUsed = j.optInt("tokens_used")
                contextBudget = j.optInt("budget_tokens")
                contextOver = j.optBoolean("over_budget")
            }
        }
    }

    /** `POST /api/context/compact` — compacts the active session, then reloads it. */
    fun compactContext() {
        val host = boundHost
        val token = boundToken
        val sid = sessionId ?: run {
            statusMessage = "Nessuna sessione attiva da compattare"
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(
                        host, token, "/api/context/compact", "POST",
                        jsonBody = JSONObject().put("session", sid).toString()
                    )
                }
            }
            result.onSuccess { body ->
                val j = runCatching { JSONObject(body) }.getOrNull()
                statusMessage = "Compattato: ${j?.optInt("input_tokens") ?: "?"} → ${j?.optInt("tokens_after") ?: "?"} token"
                switchSession(sid)
            }.onFailure { statusMessage = "Errore compaction: ${it.message?.take(120)}" }
        }
    }

    /** `GET /api/tasks` — seed the breakdown before the live feed takes over. */
    fun refreshTasks() {
        val host = boundHost
        val token = boundToken
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { rest.text(host, token, "/api/tasks") } }
            result.onSuccess { tasks = parseTasks(it) }
        }
    }

    /** `GET /api/selfcheck` — proves the chat path (auth + router) is healthy. */
    fun testConnection(host: String, token: String) {
        if (selfChecking) return
        selfChecking = true
        selfCheck = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { SelfCheckClient().test(host, token) }
            selfCheck = outcome
            selfChecking = false
        }
    }

    /**
     * Quick-action "Dove sei / comandi": it probes `/api/selfcheck` first, so the
     * outcome is visible even when the LLM is still cold, then invokes the `self`
     * tool via the agent loop.
     */
    fun askSelf() {
        val host = boundHost
        val token = boundToken
        viewModelScope.launch {
            statusMessage = "Dove sei · selfcheck in corso…"
            val outcome = withContext(Dispatchers.IO) { SelfCheckClient().test(host, token) }
            selfCheck = outcome
            statusMessage = "Dove sei · ${outcome.title} — ${outcome.detail}".take(200)
            runAgent(host, token, SELF_GOAL)
        }
    }

    /** Live `tasks.update` / session / compaction events from the durable SSE feed. */
    private fun startTaskFeed(host: String, token: String) {
        tasksJob?.cancel()
        tasksSse?.close()
        tasksJob = viewModelScope.launch {
            val since = withContext(Dispatchers.IO) { runCatching { rest.latestFeedId(host, token) }.getOrDefault(0L) }
            val sse = SseClient()
            tasksSse = sse
            val channel = Channel<SseEvent>(Channel.UNLIMITED)
            launch(Dispatchers.IO) {
                try {
                    sse.stream(host, "/api/feed?since=$since", token, onOpen = {}, onEvent = { channel.trySend(it) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    channel.trySend(SseEvent(null, "__closed__", e.message ?: ""))
                }
                channel.close()
            }
            for (event in channel) {
                when (event.type) {
                    "tasks.update" -> event.json()?.optJSONArray("tasks")?.let { tasks = parseTaskArray(it) }
                    "graph.node.added", "graph.node.updated" -> {
                        val j = event.json()
                        parseGraphNode(j?.optJSONObject("node"))?.let {
                            upsertNode(j?.optString("run").orEmpty(), it)
                        }
                    }
                    "graph.generated" -> statusMessage =
                        "task graph: ${event.json()?.optInt("nodes") ?: 0} nodi dal modello"
                    "graph.finalized" -> {
                        statusMessage = "grafo finalizzato con evidenza"
                        graphRunId?.let { refreshGraph(it) }
                    }
                    "session.created", "session.deleted" -> refreshSessions()
                    "context.compact" -> refreshContext()
                }
            }
        }
    }

    private fun launchStream(
        host: String,
        token: String,
        path: String,
        onOpen: () -> Unit,
        onEvent: (SseEvent) -> Unit,
        onEnd: (String?) -> Unit
    ): Job {
        val channel = Channel<SseEvent>(Channel.UNLIMITED)
        viewModelScope.launch(Dispatchers.IO) {
            var failure: String? = null
            try {
                client.stream(host, path, token, onOpen = {
                    channel.trySend(SseEvent(null, "__open__", "{}"))
                    onOpen()
                }, onEvent = { channel.trySend(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = describeNetworkFailure(host, e)
            }
            channel.trySend(SseEvent(null, "__closed__", failure ?: ""))
            channel.close()
        }
        return viewModelScope.launch {
            var closedError: String? = null
            for (event in channel) {
                when (event.type) {
                    "__open__" -> Unit
                    "__closed__" -> closedError = event.data.ifEmpty { null }
                    else -> onEvent(event)
                }
            }
            onEnd(closedError)
        }
    }

    private fun handleChatEvent(event: SseEvent) {
        when (event.type) {
            "model.loading" -> coldStart = "LLM in avvio… (" +
                event.json()?.optString("model").orEmpty().ifEmpty { "caricamento" } + ")"
            "model.ready" -> {
                coldStart = null
                val seconds = event.json()?.optDouble("seconds", -1.0) ?: -1.0
                if (seconds >= 0) banner = "modello pronto in ${seconds.toInt()} s"
            }
            "model.load_failed" -> {
                coldStart = null
                error = "Caricamento modello fallito: " +
                    event.json()?.optString("error").orEmpty().ifEmpty { "timeout" }.take(160)
            }
            "chat.run" -> {
                val rid = event.json()?.optString("run_id").orEmpty()
                banner = "run $rid"
                if (rid.isNotEmpty()) {
                    graphRunId = rid
                    refreshGraph(rid)
                }
            }
            "graph.node.added", "graph.node.updated" -> {
                val j = event.json()
                parseGraphNode(j?.optJSONObject("node"))?.let {
                    upsertNode(j?.optString("run").orEmpty(), it)
                }
            }
            "graph.generated" -> banner = "task graph: ${event.json()?.optInt("nodes") ?: 0} nodi"
            "chat.delta" -> {
                val json = event.json() ?: return
                val text = json.optString("text")
                if (text.isEmpty()) return
                json.optString("session").takeIf { it.isNotEmpty() }?.let {
                    sessionId = it
                    activeSession = it
                }
                if (json.optString("channel") == "think") {
                    reasoning = (reasoning + text).takeLast(20_000)
                }
                val index = streamingIndex ?: return
                val current = messages.getOrNull(index) ?: return
                val updated = if (json.optString("channel") == "think") {
                    current.copy(thinking = (current.thinking ?: "") + text)
                } else {
                    current.copy(text = current.text + text)
                }
                messages = messages.toMutableList().also { it[index] = updated }
            }
            "error" -> error = "Chat: " + (event.json()?.optString("error") ?: "errore").take(160)
        }
    }

    private fun handleAgentEvent(event: SseEvent) {
        val json = event.json() ?: JSONObject()
        when (event.type) {
            "run" -> {
                val rid = json.optString("run_id")
                banner = "agent $rid"
                if (rid.isNotEmpty()) {
                    graphRunId = rid
                    refreshGraph(rid)
                }
            }
            "graph.node.added", "graph.node.updated" -> {
                parseGraphNode(json.optJSONObject("node"))?.let { upsertNode(json.optString("run"), it) }
            }
            "graph.finalized" -> append("🧩 grafo: ${json.optInt("closed")} nodi chiusi con evidenza")
            "agent.start" -> append("▶ obiettivo: ${json.optString("goal").take(160)}")
            "agent.iteration" -> append("— iterazione ${json.optInt("i")}/${json.optInt("of")}")
            "agent.think" -> liveThinking += json.optString("text")
            "agent.thought" -> {
                val thought = json.optString("thought").trim()
                if (thought.isNotEmpty()) append("🧠 $thought")
                val action = json.optString("action")
                if (action.isNotEmpty() && action != "finish") append("⚡ $action")
                liveThinking = ""
            }
            "agent.observation" -> append("👁 ${json.optString("observation").take(400)}")
            "agent.finish" -> append("🏁 ${json.optString("summary").take(200)}")
            "agent.aborted" -> append("⛔ interrotto: ${json.optString("summary").take(160)}")
            "agent.error" -> error = "Agente: " + json.optString("error").take(160)
            "approval.request" -> append("🛡 approvazione richiesta · ${json.optString("tool")} · #${json.optString("id")}")
            "approval.resolved" -> append("🛡 approvazione ${json.optString("status")} · #${json.optString("id")}")
            "tool.call" -> append("🔧 ${json.optString("tool")}")
            "tool.result" -> append("✅ ${json.optString("tool")} ok=${json.optBoolean("ok")}")
            "error" -> error = "Agente: " + (json.optString("error").ifEmpty { "errore" }).take(160)
        }
    }

    private fun append(line: String) {
        agentLines = agentLines + line
    }

    private fun finishStreaming() {
        val index = streamingIndex ?: return
        messages.getOrNull(index)?.let { current ->
            messages = messages.toMutableList().also { it[index] = current.copy(streaming = false) }
        }
        streamingIndex = null
    }

    override fun onCleared() {
        client.close()
        tasksSse?.close()
        super.onCleared()
    }
}

@Composable
fun ForgeScreen(
    host: String,
    token: String,
    onSaveConfig: (String, String) -> Unit = { _, _ -> },
    model: ForgeViewModel = viewModel(key = "forge|$host|$token")
) {
    val listState = rememberLazyListState()
    LaunchedEffect(model.messages.size) {
        if (model.messages.isNotEmpty()) listState.animateScrollToItem(model.messages.size - 1)
    }
    var chatInput by remember { mutableStateOf("") }
    var agentInput by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }

    LaunchedEffect(host, token) { model.bind(host, token) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ── header: run banner + plan status ──
        Column(Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SPARKFORGE · SSE LIVE",
                    color = FTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    when {
                        model.coldStart != null -> "LLM IN AVVIO"
                        model.busy -> "STREAMING"
                        else -> "IDLE"
                    },
                    color = when {
                        model.coldStart != null -> FAmber
                        model.busy -> FMint
                        else -> FTextMuted
                    }, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { showConfig = !showConfig },
                    modifier = Modifier.padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FPanelRaised, contentColor = FTextMain)
                ) { Text("⚙", fontSize = 12.sp) }
            }
            model.coldStart?.let {
                Text(
                    "⏳ $it — prima risposta possibile entro qualche minuto, attendi senza inviare.",
                    color = FAmber, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (model.banner.isNotBlank()) Text(model.banner, color = FBlue, fontSize = 11.sp)
            model.error?.let { Text(it, color = FCoral, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (showConfig) ForgeConfigPanel(host, token, model, onSaveConfig)
        }

        // ── v0.5 toolbar: sessions / tasks / CoT / compact / self ──
        ForgeToolbar(model)

        // ── transcript ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(model.messages) { message -> ForgeBubble(message) }
            if (model.liveThinking.isNotBlank()) {
                item {
                    Text(
                        "🧠 ${model.liveThinking.takeLast(600)}",
                        color = FViolet, fontSize = 11.sp
                    )
                }
            }
        }

        // ── agent trace (thought → action → observation) ──
        if (model.agentLines.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 160.dp).background(FPanel)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("AGENTE · TRACE LIVE", color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(model.agentLines) { line ->
                        Text(line, color = FTextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── agent bar ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = agentInput,
                onValueChange = { agentInput = it },
                label = { Text("Obiettivo agente ⚡") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                enabled = !model.busy
            )
            Button(
                onClick = { model.runAgent(host, token, agentInput.trim()); agentInput = "" },
                enabled = !model.busy && agentInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = FViolet, contentColor = FInk)
            ) { Text("⚡") }
        }

        // ── chat bar ──
        Row(
            Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                label = { Text("Messaggio a SparkForge") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                enabled = !model.busy
            )
            if (model.busy) {
                Button(
                    onClick = { model.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = FCoral, contentColor = FInk)
                ) { Text("STOP", fontSize = 12.sp) }
            } else {
                Button(
                    onClick = { model.sendChat(host, token, chatInput.trim()); chatInput = "" },
                    enabled = chatInput.isNotBlank()
                ) { Text("SEND") }
            }
        }
    }
}

@Composable
private fun ForgeBubble(message: ForgeMessage) {
    val isYou = message.role == "you"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (isYou) Alignment.End else Alignment.Start
    ) {
        Text(
            if (isYou) "TU" else if (message.role == "system") "· · ·" else "SPARKFORGE",
            color = FTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .background(if (message.role == "system") Color.Transparent else FPanel, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (!message.thinking.isNullOrBlank()) {
                    Text("🧠 ragionamento", color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(message.thinking, color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                val body = when {
                    message.text.isNotEmpty() -> message.text
                    message.streaming -> "…"
                    else -> ""
                }
                if (body.isNotEmpty()) {
                    Text(
                        body,
                        color = if (message.role == "system") FTextMuted else FTextMain,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
