package com.jagones.sparkpulse

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// v1.6.31 (JAG-96): palette allineata al redesign della WebUI (index.html).
// Un'unica identità visiva tra PC e telefono: stessi accenti, stesse superfici.
// JAG-97: i token vivono in ForgeTheme.kt (single source of truth). Gli alias
// `F*` mantengono i nomi usati dalle ~2100 righe di questo file.
internal val FInk = ForgeInk            // app background  (--bg)
internal val FPanel = ForgePanel        // panel surface   (--bg-2)
internal val FPanelRaised = ForgePanelRaised // raised panel (--bg-3)
internal val FTextMain = ForgeTextMain  // primary text    (--txt)
internal val FTextMuted = ForgeTextMuted // muted text     (--dim)
internal val FLine = ForgeLine          // borders         (--line-2)
internal val FMint = ForgeMint          // ok              (--ok)
internal val FBlue = ForgeBlue          // info            (--info)
internal val FYou = ForgeYou            // user accent     (--you)
internal val FAmber = ForgeAmber        // warn            (--warn)
internal val FCoral = ForgeCoral        // err             (--err)
internal val FViolet = ForgeViolet      // accent 2        (--acc2)
internal val FAccent = ForgeAccent      // brand accent    (--accent)

/** Quick-action "Dove sei / comandi": goal that makes the agent invoke the v0.5 `self` tool. */
internal const val SELF_GOAL =
    "Chiama il tool self e riporta dove sei: percorsi repo/data/sessioni, config, docs, " +
    "stato del servizio SparkForge e come aggiungere skill o server MCP."

/** v1.6.30 (JAG-89): stable per-bubble identity. `copy()` during streaming
 * preserves it, so UI state keyed on `id` (the CoT expander) survives every
 * chat.delta instead of being reset (the chevron closed itself mid-stream). */
private val forgeMsgId = java.util.concurrent.atomic.AtomicLong(0)

/** One chat exchange kept in the Forge transcript. */
data class ForgeMessage(
    val role: String, // "you" | "forge" | "system" | "tool"
    val text: String,
    val thinking: String? = null,
    val streaming: Boolean = false,
    /** v1.6.3 (JAG-55): when set, this transcript entry is an inline tool mini-card. */
    val tool: ForgeToolCard? = null,
    /** v1.6.26 (JAG-79): when set, this transcript entry is an inline approval card. */
    val approval: ForgeApproval? = null,
    val id: Long = forgeMsgId.incrementAndGet()
)

/** v1.6.9 (JAG-58d): renders a JSON value compact-pretty for the tool card. */
internal fun prettyToolArgs(value: Any?): String = when (value) {
    null -> ""
    is JSONObject -> value.toString(2)
    else -> value.toString()
}

/** One tool call/result rendered inline in the chat transcript (v1.6.3). */
data class ForgeToolCard(
    val tool: String,
    val ok: Boolean? = null,
    val summary: String = "",
    val exitCode: Int? = null,
    val backend: String? = null,
    val result: String = "",
    // v1.6.9 (JAG-58d): input (args) + error (stderr) so the card can expand
    // into a full input/output/error inspector.
    val args: String = "",
    val error: String = ""
)

/** Maps a `tool.call` SSE payload to a pending inline card (v1.6.3). */
internal fun toolCardFromCall(data: JSONObject): ForgeToolCard =
    ForgeToolCard(
        tool = data.optString("tool").ifEmpty { "tool" },
        args = prettyToolArgs(data.opt("args"))
    )

/** Maps a `tool.result` SSE payload to a completed inline card (v1.6.3). */
internal fun toolCardFromResult(data: JSONObject): ForgeToolCard = ForgeToolCard(
    tool = data.optString("tool").ifEmpty { "tool" },
    ok = data.optBoolean("ok"),
    summary = data.optString("summary").take(160),
    exitCode = if (data.has("exit_code")) data.optInt("exit_code") else null,
    backend = data.optString("backend").takeIf { it.isNotEmpty() },
    result = data.optString("stdout"),
    error = data.optString("stderr")
)

/** One persisted SparkForge session, as returned by `GET /api/sessions` (v0.5). */
data class ForgeSession(val id: String, val title: String, val messages: Int, val created: Double)

/**
 * v1.6.17 (JAG-71): one selectable model from the provider catalogue
 * (`GET /api/providers`). `ref` is the `<provider>:<model>` reference sent to the
 * server; `loaded` is only meaningful for local servers (null otherwise).
 */
data class ForgeModelRef(val id: String, val ref: String, val loaded: Boolean? = null)

/** v1.6.17 (JAG-71): a provider and its models (llama.cpp DGX/Windows, vLLM, OpenRouter, DeepSeek). */
data class ForgeProvider(
    val id: String,
    val name: String,
    val available: Boolean,
    val local: Boolean,
    val models: List<ForgeModelRef>
)

/** Parses the `{"providers":[...]}` payload of `GET /api/providers`. */
internal fun parseProviders(body: String): List<ForgeProvider> {
    val arr = runCatching { JSONObject(body).optJSONArray("providers") }.getOrNull()
        ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val p = arr.optJSONObject(i) ?: return@mapNotNull null
        val ms = p.optJSONArray("models") ?: JSONArray()
        val models = (0 until ms.length()).mapNotNull { j ->
            val m = ms.optJSONObject(j) ?: return@mapNotNull null
            ForgeModelRef(
                id = m.optString("id"),
                ref = m.optString("ref"),
                loaded = if (m.isNull("loaded")) null else m.optBoolean("loaded")
            )
        }
        ForgeProvider(
            id = p.optString("id"), name = p.optString("name"),
            available = p.optBoolean("available", true),
            local = p.optBoolean("local", false), models = models
        )
    }
}

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

/**
 * Splits the tail of a model message into (visible, think). The visible part
 * is the reply to show as the main chat text; the tail (if any) goes to the
 * CoT drawer instead of masquerading as the answer.
 */
internal fun splitAnswerTail(content: String): Pair<String, String> {
    val trimmed = content.trimStart()
    val thinkPrefix = "think: "
    val separator = "\n$thinkPrefix"
    if (!trimmed.contains(thinkPrefix)) return content to ""
    val idx = trimmed.indexOf(separator)
    if (idx >= 0) {
        return trimmed.substring(0, idx) to trimmed.substring(idx + separator.length)
    }
    // the whole message is reasoning, no visible reply after it
    return if (trimmed.startsWith(thinkPrefix)) "" to trimmed.removePrefix(thinkPrefix) else content to ""
}

/** Visible reply part of a persisted message (main chat text). */
internal fun extractAnswerText(content: String) = splitAnswerTail(content).first

/** Reasoning tail of a persisted message (goes to the CoT drawer). */
internal fun extractThinkText(content: String) = splitAnswerTail(content).second

/**
 * v1.6.3 (JAG-55): inserts a pending tool mini-card right before the streaming
 * reply bubble, so the transcript reads user → tool call → reply (the order in
 * which the turn actually ran). Returns the new list plus the shifted index of
 * the streaming bubble.
 */
internal fun insertToolCard(
    messages: List<ForgeMessage>,
    streamingIndex: Int?,
    card: ForgeToolCard
): Pair<List<ForgeMessage>, Int?> {
    val at = (streamingIndex ?: messages.size).coerceIn(0, messages.size)
    val out = messages.toMutableList().also {
        it.add(at, ForgeMessage("tool", "", tool = card))
    }
    return out to streamingIndex?.let { it + 1 }
}

/**
 * v1.6.3 (JAG-55): fills the newest still-pending card for `tool` with its
 * outcome (`tool.result`), or appends it when no call was seen (agent runs).
 */
internal fun applyToolResult(messages: List<ForgeMessage>, card: ForgeToolCard): List<ForgeMessage> {
    val index = messages.indexOfLast { it.tool?.tool == card.tool && it.tool.ok == null }
    return if (index >= 0) {
        val prev = messages[index].tool
        // v1.6.9 (JAG-58d): `tool.result` carries no args — keep the input
        // captured at `tool.call` time so the expanded card stays complete.
        val merged = if (prev != null) card.copy(args = prev.args.ifEmpty { card.args }) else card
        messages.toMutableList().also { it[index] = it[index].copy(tool = merged) }
    } else {
        messages + ForgeMessage("tool", "", tool = card)
    }
}

/** Rebuilds the chat transcript from `GET /api/history` (messages + tool_cards). */
internal fun parseHistory(body: String): List<ForgeMessage> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val arr = root.optJSONArray("messages") ?: return emptyList()

    // JAG-96: the harness persists each inline tool card under `tool_cards`
    // (`after` = number of messages it follows) so the mirror can rebuild the
    // transcript on cold start. Cards live OUTSIDE `messages`, so they never
    // reach the model prompt. Field names MUST match ForgeToolCard.
    val byAfter = HashMap<Int, MutableList<ForgeToolCard>>()
    root.optJSONArray("tool_cards")?.let { cards ->
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            val after = if (c.has("after")) c.optInt("after") else arr.length()
            val card = ForgeToolCard(
                tool = c.optString("tool", "tool"),
                ok = if (c.has("ok")) c.optBoolean("ok") else null,
                summary = "da cronologia",
                exitCode = if (c.has("exit_code") && !c.isNull("exit_code"))
                    c.optInt("exit_code") else null,
                backend = c.optString("backend").ifEmpty { "history" },
                result = c.optString("result"),
                args = c.optString("args").ifEmpty { "{args non persistiti}" },
                error = c.optString("error")
            )
            byAfter.getOrPut(after) { mutableListOf() }.add(card)
        }
    }

    val out = mutableListOf<ForgeMessage>()
    for (i in 0 until arr.length()) {
        byAfter[i]?.forEach { out += ForgeMessage("tool", "", tool = it) }
        val m = arr.optJSONObject(i) ?: continue
        val role = when (m.optString("role")) {
            "user" -> "you"
            "assistant" -> "forge"
            else -> m.optString("role").ifEmpty { "system" }
        }
        val src = m.optString("content")
        val (answer, think) = splitAnswerTail(src)
        out += ForgeMessage(role, answer, m.optString("reasoning").ifEmpty { think.ifEmpty { null } })
    }
    byAfter[arr.length()]?.forEach { out += ForgeMessage("tool", "", tool = it) }
    return out
}

/**
 * SSE-driven Forge/chat state. Both the chat stream and the agent loop push into
 * a channel consumed on the main dispatcher, so UI deltas arrive ordered and
 * thread-safe while the blocking socket read happens on IO.
 */
/** v1.6.5 (JAG-58c): an approval raised by the agent loop, decidable from the
 *  FORGE tab so a `required` tool no longer hangs the run for 300s.
 *  v1.6.26 (JAG-79): rendered INLINE in the transcript (not a separate banner). */
data class ForgeApproval(
    val id: String,
    val tool: String,
    val summary: String,
    val runId: String?,
    val status: String = "pending"
)

/** v1.6.27 (JAG-81): one tool policy row for the SETTINGS panel. */
data class ForgeToolFlag(
    val name: String,
    val enabled: Boolean,
    val approval: String,
    val description: String
)

internal fun parseToolFlags(body: String): List<ForgeToolFlag> {
    val arr = runCatching { JSONObject(body).optJSONArray("tools") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        ForgeToolFlag(
            name = o.optString("name"),
            enabled = o.optBoolean("enabled"),
            approval = o.optString("approval").ifEmpty { "required" },
            description = o.optString("description")
        )
    }
}

internal fun parseForgeApprovals(body: String): List<ForgeApproval> {
    val array = JSONObject(body).optJSONArray("approvals") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        ForgeApproval(
            id = item.optString("id"),
            tool = item.optString("tool"),
            summary = item.optString("summary").ifEmpty { item.optString("reason") },
            runId = item.optString("run_id").takeIf { it.isNotEmpty() },
            status = item.optString("status").ifEmpty { "pending" }
        )
    }
}

class ForgeViewModel : ViewModel() {
    private val client = SseClient()
    private var streamJob: Job? = null
    private var approvalPollJob: Job? = null

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

    /** v1.6.4 (JAG-57): app context used to persist the selected session id. */
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context
    }

    private fun persistSession(id: String?) {
        appContext?.let { ForgeConfig.saveSession(it, id) }
    }

    /** v1.6.4 (JAG-57): restores the session persisted in ForgeConfig, if the
     *  ViewModel has no active session yet (e.g. after process death). */
    fun restoreSavedSession() {
        if (sessionId != null) return
        val saved = appContext?.let { ForgeConfig.session(it) }.orEmpty()
        if (saved.isNotEmpty()) switchSession(saved)
    }
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

    /** v1.6.3: "Dove sei" bottom panel — closed by default, single toggle open/close. */
    var selfOpen by mutableStateOf(false)
        private set

    /** v1.6.3: live agent trace is collapsible (open by default, always dismissible). */
    var traceOpen by mutableStateOf(true)
        private set

    /** v1.6.5 (JAG-58c): pending approvals from the agent loop, decidable here. */
    var pendingApprovals by mutableStateOf(listOf<ForgeApproval>())
        private set
    var approvalNotice by mutableStateOf<String?>(null)
        private set

    /** v1.6.26 (JAG-79): bumped when an inline approval card is added, so the UI
     *  scrolls it into view (an approval must never sit off-screen unnoticed). */
    var approvalPing by mutableStateOf(0)
        private set

    /** v1.6.27 (JAG-81): per-tool policies shown in the SETTINGS panel. */
    var tools by mutableStateOf(listOf<ForgeToolFlag>())
        private set
    var settingsOpen by mutableStateOf(false)
        private set
    var settingsNotice by mutableStateOf<String?>(null)
        private set

    /** Token indicator fed by `GET /api/context` (v1.6.3: real session values).
     *  v1.6.16 (JAG-70): `used` is the EFFECTIVE prompt size the server really
     *  sends (system prompt + transcript), not just the stored transcript. */
    /** JAG-107: the context indicator is rendered from server-computed strings
     *  (`display` block of `/api/context`), so token formatting, the percentage
     *  and the over/near state are never replicated between the two clients. */
    var ctxShort by mutableStateOf("ctx n/d")
        private set
    var ctxState by mutableStateOf("na")
        private set
    var ctxDetail by mutableStateOf("contesto n/d")
        private set
    var ctxBarPct by mutableStateOf(0)
        private set
    var ctxBarHot by mutableStateOf(false)
        private set

    /** v1.6.17 (JAG-71): provider/model catalogue (`GET /api/providers`) + the
     *  user's pick. `selectedModel` is a `<provider>:<model>` ref sent with every
     *  request; null lets the server use its configured default. */
    var providers by mutableStateOf(listOf<ForgeProvider>())
        private set
    var selectedModel by mutableStateOf<String?>(null)
        private set
    var modelPickerOpen by mutableStateOf(false)
        private set

    /** v1.6.20: the SparkForge server version (`GET /api/selfcheck`) — shown in the
     *  header so the app is visibly in sync with the server. */
    var serverVersion by mutableStateOf("")
        private set

    /** v1.6.3: messages counted server-side for the bound session (`n/d` when none). */
    var contextMessages by mutableStateOf(0)
        private set

    /** v1.6.3: false when /api/context has no session to measure → indicator "n/d". */
    var contextAvailable by mutableStateOf(false)
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
        val trimmed = text.trim()
        // JAG-78: slash commands run INSIDE the chat — no separate agent run.
        // `/help` answers locally; `/goal <x>` runs the SAME chat loop in
        // autonomous mode (the server adds the plan-first directive).
        if (trimmed == "/help" || trimmed == "/?") {
            messages = messages + ForgeMessage("you", trimmed) + ForgeMessage("forge", FORGE_COMMANDS_HELP)
            return
        }
        val isGoal = trimmed == "/goal" || trimmed.startsWith("/goal ")
        val body = if (isGoal) trimmed.removePrefix("/goal").trim() else trimmed
        if (body.isBlank()) {
            messages = messages + ForgeMessage("you", trimmed) +
                ForgeMessage("forge", "Uso: /goal <obiettivo>")
            return
        }
        val query = buildString {
            append("/api/chat/stream?message=").append(URLEncoder.encode(body, "UTF-8"))
            sessionId?.let { append("&session=").append(URLEncoder.encode(it, "UTF-8")) }
            // v1.6.17 (JAG-71): the user's chosen model (provider:model), if any.
            selectedModel?.let { append("&model=").append(URLEncoder.encode(it, "UTF-8")) }
            if (isGoal) append("&mode=goal")
        }
        graphGoal = body
        banner = if (isGoal) "obiettivo: pianifico…" else "pianifico…"
        val shown = if (isGoal) "🎯 $body" else body
        messages = messages + ForgeMessage("you", shown) + ForgeMessage("forge", "", streaming = true)
        streamingIndex = messages.lastIndex
        busy = true
        error = null
        coldStart = null
        reasoning = ""
        startApprovalPoll()
        streamJob = launchStream(host, token, query,
            onOpen = { },
            onEvent = ::handleChatEvent,
            isChat = true,
            onEnd = { failure ->
                finishStreaming()
                // v1.6.1 (JAG-49): the stream is over → clear the job and leave
                // `busy`. Previously this only happened in stop(), so a finished
                // answer left the UI busy: STOP still visible, next message
                // silently dropped by the `busy` guard.
                streamJob = null
                busy = false
                coldStart = null
                stopApprovalPoll()
                // JAG-85: ALWAYS reconcile with the server on stream end (not only
                // on a drop) — a clean `done` can still arrive with the final reply
                // missing from the bubble, which looked like "si è fermato".
                reconcileTranscript(failure)
            }
        )
    }

    /**
     * JAG-85 — reconcile the transcript with the SERVER at the end of a turn.
     *
     * JAG-79 added this only for a DROPPED socket. But a turn can also end with a
     * clean `done` while the final reply never landed in the bubble (a lost
     * `chat.delta`, an early socket close, a server restart mid-turn): the panel
     * then showed the tool cards with NO answer = "si è fermato". So we now ALWAYS
     * pull `GET /api/history` on stream end and adopt the server transcript when it
     * holds more assistant turns than we do. Idempotent and cheap on a good turn
     * (one check, no delay); on failure it polls as before.
     */
    private fun reconcileTranscript(failure: String?) {
        val host = boundHost
        val token = boundToken
        val sid = sessionId ?: activeSession
        if (host.isBlank() || sid.isNullOrBlank()) {
            if (failure != null) error = "Chat interrotta: ${failure.take(140)}"
            return
        }
        val before = messages.count { it.role == "forge" }
        val attempts = if (failure != null) 12 else 1
        error = null
        if (failure != null) banner = "connessione persa · risincronizzo…"
        viewModelScope.launch {
            var recovered: List<ForgeMessage>? = null
            for (attempt in 0 until attempts) {
                if (recovered != null) break
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        rest.text(host, token,
                            "/api/history?session=" + URLEncoder.encode(sid, "UTF-8"))
                    }.map { parseHistory(it) }.getOrNull()
                }
                val n = loaded?.count { it.role == "forge" } ?: 0
                android.util.Log.d("SparkPulse",
                    "reconcile end failure=$failure attempt=$attempt local=$before server=$n")
                if (loaded != null && n > before) {
                    recovered = loaded
                } else if (attempt < attempts - 1) {
                    delay(1500)
                }
            }
            val got = recovered
            if (got != null) {
                messages = got
                streamingIndex = null
                statusMessage = "Risposta recuperata dal server"
                banner = ""
            } else {
                if (failure != null) error = "Chat interrotta: ${failure.take(140)}"
                banner = ""
            }
            refreshContext()
            refreshGraph(sid)
        }
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
        startApprovalPoll()
        val query = "/api/agent/run?goal=" + URLEncoder.encode(goal, "UTF-8") + "&max_steps=6" +
            (selectedModel?.let { "&model=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        streamJob = launchStream(host, token, query,
            onOpen = { },
            onEvent = ::handleAgentEvent,
            onEnd = { failure ->
                failure?.let { error = "Agente interrotto: ${it.take(140)}" }
                streamJob = null
                busy = false
                liveThinking = ""
                stopApprovalPoll()
            }
        )
    }

    /**
     * Drops the socket and cancels the current SSE stream (chat or agent).
     * v1.6.15 (JAG-68): it also STOPS the tool subprocess still running for this
     * session on the server (`POST /api/tools/cancel`), so a long shell/MCP job
     * is actually killed and not just the socket.
     */
    fun stop() {
        cancelRunningTool()
        client.close()
        streamJob?.cancel()
        streamJob = null
        finishStreaming()
        busy = false
        liveThinking = ""
        stopApprovalPoll()
    }

    /** JAG-68: kill the server-side tool job bound to the active session. */
    private fun cancelRunningTool() {
        val host = boundHost
        val token = boundToken
        val sid = sessionId ?: activeSession
        if (host.isBlank() || sid.isNullOrBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token, "/api/tools/cancel", "POST",
                              jsonBody = JSONObject().put("run_id", sid).toString())
                }
            }
        }
    }

    // ── v0.5: sessions, context/compaction, tasks feed, self-knowledge ──

    /** Binds the panel endpoints to the configured host/token (idempotent).
     *  v1.6.4 (JAG-57): also restores the last selected session from prefs. */
    fun bind(context: Context, host: String, token: String) {
        appContext = context.applicationContext
        if (boundHost == host && boundToken == token) return
        boundHost = host
        boundToken = token
        refreshSessions()
        refreshContext()
        refreshTasks()
        refreshApprovals()
        refreshProviders()
        refreshServerVersion()
        startTaskFeed(host, token)
        if (activeSession == null) {
            appContext?.let { ForgeConfig.session(it) }?.let { switchSession(it) }
        }
    }

    /** v1.6.4 (JAG-57): persists the selected session id in SharedPreferences. */
    private fun rememberSession() {
        appContext?.let { ForgeConfig.saveSession(it, sessionId) }
    }

    fun toggleCot() {
        cotOpen = !cotOpen
    }

    /**
     * v1.6.3 — opens/closes the "Dove sei" panel. Opening runs the selfcheck probe
     * (and the `self` agent loop); closing dismisses the panel, so the user can
     * always get rid of it again (previously it stayed on screen forever).
     */
    fun toggleSelf() {
        selfOpen = !selfOpen
        if (selfOpen) askSelf()
    }

    /** v1.6.3: collapses/expands the live agent trace panel. */
    fun toggleTrace() {
        traceOpen = !traceOpen
    }

    /** v1.6.5 (JAG-58c): pull the pending approval queue (best-effort). */
    fun refreshApprovals() {
        val host = boundHost
        val token = boundToken
        if (host.isEmpty()) return
        viewModelScope.launch {
            // v1.6.10 (JAG-58c fix): the REST call MUST run off the main thread,
            // otherwise Android throws NetworkOnMainThreadException and the
            // queue stays empty — which is exactly why the banner never showed.
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    parseForgeApprovals(rest.text(host, token, "/api/approvals?status=pending&limit=50"))
                }.getOrDefault(emptyList())
            }
            pendingApprovals = list
            syncApprovalMessages(list)
        }
    }

    /**
     * v1.6.26 (JAG-79): keep the approvals INLINE in the transcript — the chat is
     * the single surface (no separate banner above the input). Newly pending
     * approvals become an inline card right before the streaming bubble; ones the
     * server no longer lists are marked resolved. Driven both by the
     * `approval.*` SSE events and by the 3s poll, so a dropped event still shows.
     */
    private fun syncApprovalMessages(list: List<ForgeApproval>) {
        val ids = list.map { it.id }.toSet()
        var changed = false
        var out = messages.map { m ->
            val ap = m.approval
            if (ap != null && ap.status == "pending" && ap.id !in ids) {
                changed = true
                m.copy(approval = ap.copy(status = "resolved"))
            } else m
        }
        list.forEach { ap ->
            if (out.none { it.approval?.id == ap.id }) {
                val at = (streamingIndex ?: out.size).coerceIn(0, out.size)
                out = out.toMutableList().also { it.add(at, ForgeMessage("approval", "", approval = ap)) }
                streamingIndex?.let { streamingIndex = it + 1 }
                changed = true
                approvalPing++  // v1.6.26: pull the new card into view
            }
        }
        if (changed) messages = out
    }

    // ── v1.6.27 (JAG-81): SETTINGS — per-tool approval policy ──

    /** Open/close the SETTINGS panel; opening loads the tool catalog. */
    fun toggleSettings() {
        settingsOpen = !settingsOpen
        if (settingsOpen) {
            settingsNotice = null
            refreshTools()
        }
    }

    /** `GET /api/tools` — the catalog with enabled/approval per tool. */
    fun refreshTools() {
        val host = boundHost
        val token = boundToken
        if (host.isEmpty()) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { parseToolFlags(rest.text(host, token, "/api/tools")) }
                    .getOrDefault(emptyList())
            }
            // enabled tools first, then alphabetical — the useful ones up top.
            tools = list.sortedWith(compareBy({ !it.enabled }, { it.name }))
        }
    }

    /** `POST /api/tools` — flip a tool's approval policy and/or enabled flag. */
    fun setToolPolicy(name: String, auto: Boolean? = null, enabled: Boolean? = null) {
        val host = boundHost
        val token = boundToken
        if (host.isEmpty() || name.isEmpty()) return
        val body = JSONObject().put("tool", name)
        auto?.let { body.put("approval", if (it) "auto" else "required") }
        enabled?.let { body.put("enabled", it) }
        settingsNotice = "Salvo $name…"
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token, "/api/tools", "POST", jsonBody = body.toString())
                    true
                }.getOrDefault(false)
            }
            settingsNotice = if (ok) "$name aggiornato" else "Errore su $name"
            refreshTools()
        }
    }

    /** v1.6.5 (JAG-58c): approve/deny a pending approval (chat & agent HITL).
     *  Without this the FORGE tab could not answer an `approval.request`, so a
     *  `required` tool blocked the run until the 300s server timeout. */
    fun decideApproval(id: String, decision: String) {
        val host = boundHost
        val token = boundToken
        if (host.isEmpty() || id.isEmpty()) return
        android.util.Log.d("SparkPulse", "decideApproval $id -> $decision\n" +
            android.util.Log.getStackTraceString(Throwable()))
        approvalNotice = if (decision == "approve") "Invio approvazione…" else "Invio rifiuto…"
        viewModelScope.launch {
            // v1.6.10 (JAG-58c fix): POST off the main thread — a network call
            // on Main throws NetworkOnMainThreadException, so the Approve/Deny
            // tap would silently do nothing.
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token, "/api/approvals/$id", "POST",
                        JSONObject().put("decision", decision).put("by", "forge-ui").toString())
                    true
                }.getOrDefault(false)
            }
            approvalNotice = if (ok) "Decisione inviata" else "Errore invio decisione"
            refreshApprovals()
        }
    }

    /** v1.6.5 (JAG-58c): polls the approval queue while a run is live, so a
     *  `required` tool surfaces its Approve/Deny buttons promptly. */
    private fun startApprovalPoll() {
        approvalPollJob?.cancel()
        approvalPollJob = viewModelScope.launch {
            while (true) {
                refreshApprovals()
                delay(2000)
            }
        }
    }

    private fun stopApprovalPoll() {
        approvalPollJob?.cancel()
        approvalPollJob = null
        refreshApprovals()
    }

    /** v1.6.3: clears the transient panel feedback line. */
    fun dismissStatus() {
        statusMessage = null
    }

    // ── v0.6: task graph ──

    /** Opens/closes the graph panel and re-syncs it from the server. */
    fun toggleGraph() {
        graphOpen = !graphOpen
        // v1.6.21 (JAG-76): the graph lives under the SESSION key; fall back to the
        // bound session so the panel is populated even without a fresh chat.run.
        (graphRunId ?: sessionId ?: activeSession)?.let { refreshGraph(it) }
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

    /**
     * JAG-86: the plan is PERSISTENT by design (it survives every message, like
     * Claude Code's task list / Deep Agents' todo file), so it must be clearable
     * ON PURPOSE. The server already exposes
     * `POST /api/sessions/<sid>/graph/reset` (JAG-63) but the app never called
     * it — the list felt stuck forever ("se clicco annulla non si cancella").
     */
    fun clearPlan() {
        val host = boundHost
        val token = boundToken
        val sid = graphRunId ?: sessionId ?: activeSession
        graphNodes = emptyList()
        selectedNode = null
        statusMessage = "Piano cancellato"
        if (host.isBlank() || sid.isNullOrBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token,
                        "/api/sessions/" + URLEncoder.encode(sid, "UTF-8") + "/graph/reset",
                        "POST")
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
    fun updateGraphDraft(text: String) {
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
                    rememberSession()
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
        graphRunId = id  // v1.6.21 (JAG-76): the graph is keyed by the session
        rememberSession()
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
            refreshGraph(id)
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
                rememberSession()
                messages = listOf(ForgeMessage("system", "Sessione eliminata. Chat senza sessione."))
            }
            statusMessage = "Sessione eliminata: $id"
            refreshSessions()
            // v1.6.3: the indicator must not keep the deleted session's numbers
            refreshContext()
        }
    }

    /**
     * v1.6.17 (JAG-71): loads the provider/model catalogue for the picker and
     * restores the persisted choice, so the app can drive ANY configured model
     * (local llama.cpp DGX/Windows, vLLM, OpenRouter, DeepSeek original).
     */
    fun refreshProviders() {
        val host = boundHost
        val token = boundToken
        if (host.isBlank()) return
        if (selectedModel == null) selectedModel = appContext?.let { ForgeConfig.model(it) }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { parseProviders(rest.text(host, token, "/api/providers")) }
                    .getOrDefault(emptyList())
            }
            if (list.isNotEmpty()) providers = list
        }
    }

    /** v1.6.20: read the server version so the header proves app↔server alignment. */
    fun refreshServerVersion() {
        val host = boundHost
        val token = boundToken
        if (host.isBlank()) return
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { rest.text(host, token, "/api/selfcheck") }.getOrNull()
            } ?: return@launch
            val v = runCatching { JSONObject(body).optString("version") }.getOrNull()
            if (!v.isNullOrBlank()) serverVersion = v
        }
    }

    fun toggleModelPicker() {
        modelPickerOpen = !modelPickerOpen
        if (modelPickerOpen) refreshProviders()
    }

    /** Picks the model used by chat + agent (`null` = server default) and persists it. */
    fun selectModel(ref: String?) {
        selectedModel = ref
        appContext?.let { ForgeConfig.saveModel(it, ref) }
        statusMessage = if (ref.isNullOrBlank()) "modello: default del server"
        else "modello: $ref"
        refreshContext()
    }

    /** `GET /api/context` — token usage vs budget for the active session (v1.6.3:
     *  without a session the indicator reads n/d instead of fake defaults). */
    fun refreshContext() {
        val host = boundHost
        val token = boundToken
        val sid = sessionId
        if (sid.isNullOrBlank()) {
            contextMessages = 0
            applyCtxDisplay(null)
            return
        }
        viewModelScope.launch {
            val path = "/api/context?session=" + URLEncoder.encode(sid, "UTF-8") +
                (selectedModel?.let { "&model=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val result = withContext(Dispatchers.IO) { runCatching { rest.text(host, token, path) } }
            result.onSuccess { body ->
                val j = runCatching { JSONObject(body) }.getOrNull() ?: return@onSuccess
                contextMessages = if (j.optBoolean("available", true)) j.optInt("messages") else 0
                applyCtxDisplay(j.optJSONObject("display"))
            }
        }
    }

    /** JAG-107: bind the server-computed context display block verbatim — the
     *  single source of truth for the meter string, state and bar fill. */
    private fun applyCtxDisplay(d: JSONObject?) {
        if (d == null) {
            contextAvailable = false
            ctxState = "na"
            ctxShort = "ctx n/d"
            ctxDetail = "contesto n/d"
            ctxBarPct = 0
            ctxBarHot = false
            return
        }
        contextAvailable = d.optBoolean("available", false)
        ctxState = d.optString("state", if (contextAvailable) "normal" else "na")
        ctxShort = d.optString("short", "ctx n/d")
        ctxDetail = d.optString("detail", "contesto n/d")
        ctxBarPct = d.optInt("bar_pct", 0)
        ctxBarHot = d.optBoolean("bar_hot", false)
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

                    // JAG-90: Re-attach to live chat stream if the app was reopened mid-turn
                    "chat.delta", "tool.call", "tool.result", "chat.done" -> {
                        val j = event.json()
                        if (j != null && j.optString("session") == activeSession && streamJob == null) {
                            handleChatEvent(event)
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
                    // v1.6.26 (JAG-79): the durable feed carries approval life-cycle
                    // events too, so a gated tool shows its INLINE card even when no
                    // chat turn is streaming (the 3s poll is only active in a turn).
                    "approval.request", "approval.resolved", "approval.decided" -> refreshApprovals()
                    // JAG-96: deterministic process-reward (PRM) verification report
                    // from the harness — surfaced so inefficiencies are visible.
                    "prm.feedback" -> {
                        val j = event.json()
                        val score = j?.optDouble("score") ?: 0.0
                        statusMessage = "verify ${"%.2f".format(score)} · " +
                            j?.optString("feedback").orEmpty().take(90)
                    }
                    "context.built" -> {
                        // JAG-107: live, authoritative context display for this turn.
                        applyCtxDisplay(event.json()?.optJSONObject("display"))
                    }
                    "context.auto_compact" -> {
                        statusMessage = "auto-compaction: contesto oltre il " +
                            "${event.json()?.optDouble("threshold")?.toInt() ?: 75}%"
                        refreshContext()
                    }
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
        onEnd: (String?) -> Unit,
        isChat: Boolean = false
    ): Job {
        val channel = Channel<SseEvent>(Channel.UNLIMITED)
        val producer = viewModelScope.launch(Dispatchers.IO) {
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
            var terminal = false
            for (event in channel) {
                when (event.type) {
                    "__open__" -> Unit
                    "__closed__" -> closedError = event.data.ifEmpty { null }
                    else -> {
                        onEvent(event)
                        // v1.6.1 (JAG-49): `done` is the end of stream. Finalize
                        // the UI state now and drop the socket, without waiting
                        // for an EOF the server may never send.
                        if (isTerminalSseEvent(event.type)) {
                            if (isChat) promoteThinkToReply()
                            terminal = true
                            break
                        }
                    }
                }
            }
            if (terminal) {
                client.close()
                producer.cancel()
            }
            channel.cancel()
            onEnd(closedError)
        }
    }

    private fun handleChatEvent(event: SseEvent) {
        // v1.6.3 (JAG-55): the first turn of a session is created server-side and
        // its id is only carried by the SSE payloads. Capture it from ANY event
        // (chat.run graph/tool/delta/done) so the context indicator queries
        // /api/context with the real session instead of showing 6000/0.
        event.json()?.optString("session")?.takeIf { it.isNotEmpty() }?.let {
            sessionId = it
            activeSession = it
            rememberSession()
        }
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
                val j = event.json()
                val rid = j?.optString("run_id").orEmpty()
                banner = "run $rid"
                // v1.6.21 (JAG-76): the chat task graph is persisted under the
                // SESSION key, not the ephemeral trace id. Binding the panel to
                // the trace id made every refresh query an empty graph → the plan
                // vanished the moment the panel was re-opened. Use the session.
                val key = j?.optString("session").orEmpty().ifEmpty { sessionId ?: "" }
                if (key.isNotEmpty()) {
                    graphRunId = key
                    refreshGraph(key)
                }
            }
            "graph.node.added", "graph.node.updated" -> {
                val j = event.json()
                val key = j?.optString("run_id").orEmpty()
                    .ifEmpty { j?.optString("session").orEmpty() }
                    .ifEmpty { j?.optString("run").orEmpty() }
                    .ifEmpty { graphRunId ?: "" }
                parseGraphNode(j?.optJSONObject("node"))?.let { upsertNode(key, it) }
            }
            "graph.generated" -> banner = "task graph: ${event.json()?.optInt("nodes") ?: 0} nodi"
            // JAG-78: live prompt size for THIS turn → the ctx meter updates as
            // soon as the turn starts, not only at the end.
            "context.built" -> applyCtxDisplay(event.json()?.optJSONObject("display"))
            "chat.delta" -> {
                val json = event.json() ?: return
                val text = json.optString("text")
                if (text.isEmpty()) return
                json.optString("session").takeIf { it.isNotEmpty() }?.let {
                    sessionId = it
                    activeSession = it
                    rememberSession()
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
            "tool.call" -> event.json()?.let(::addToolCard)
            "tool.result" -> event.json()?.let(::updateToolCard)
            // v1.6.26 (JAG-79): approvals surface INLINE in the chat transcript.
            "approval.request", "approval.resolved", "approval.decided" -> refreshApprovals()
            "error" -> error = "Chat: " + (event.json()?.optString("error") ?: "errore").take(160)
        }
    }

    /**
     * v1.6.3 (JAG-55): a `tool.call` becomes an inline mini-card inserted right
     * before the streaming reply bubble, so the transcript reads like the run
     * itself — user → tool call (+outcome) → reply — and stays coherent with the
     * task-graph nodes the very same call produced.
     */
    private fun addToolCard(json: JSONObject?) {
        val data = json ?: return
        val (list, shifted) = insertToolCard(messages, streamingIndex, toolCardFromCall(data))
        messages = list
        // the reply bubble moved one slot down: keep streaming deltas on it
        streamingIndex = shifted
    }

    /** v1.6.3 (JAG-55): `tool.result` fills the pending card with its outcome. */
    private fun updateToolCard(json: JSONObject?) {
        val data = json ?: return
        messages = applyToolResult(messages, toolCardFromResult(data))
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
            "prm.feedback" -> append(
                "📉 verify ${"%.2f".format(json.optDouble("score"))} · " +
                    json.optString("feedback").take(120))
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

    /**
     * v1.6.3 (JAG-55): at the end of a chat stream, if the model produced only
     * reasoning and no visible answer, promote the reasoning tail to the main
     * text (the CoT drawer keeps a copy) so the user always sees a reply and
     * never a message bubble stuck on "…".
     */
    private fun promoteThinkToReply() {
        val index = streamingIndex ?: return
        val current = messages.getOrNull(index) ?: return
        // JAG-78b: never leave raw tool-call JSON in the bubble. Defensive: the
        // server no longer streams it, this covers truncated/partial leaks.
        if (looksLikeJsonAction(current.text)) {
            messages = messages.toMutableList().also {
                it[index] = current.copy(text = "(azione eseguita · nessun testo da mostrare)")
            }
            return
        }
        if (current.text.isNotBlank()) return
        val reply = splitAnswerTail(reasoning).second.ifBlank { reasoning.takeLast(2_000) }
        if (reply.isBlank()) return
        messages = messages.toMutableList().also {
            it[index] = current.copy(text = reply)
        }
    }

    override fun onCleared() {
        client.close()
        tasksSse?.close()
        super.onCleared()
    }
}

/**
 * JAG-78: helper text shown by `/help`. Commands run INSIDE the chat — there is
 * no separate agent session/run any more.
 */
internal val FORGE_COMMANDS_HELP = """
Comandi disponibili (nella chat, stessa sessione):
• /goal <obiettivo> — modalità autonoma: il modello pianifica (write_todos),
  esegue i passi con i tool e aggiorna il grafo, senza chiedere conferma.
• /help — mostra questo elenco.
""".trim()

/**
 * JAG-78b: true when a streamed assistant text is a raw tool-call JSON payload
 * (`{"action":…}`) that must never be shown to the user as a chat bubble.
 */
internal fun looksLikeJsonAction(text: String): Boolean {
    val t = text.trim()
    return t.length in 8..4000 && t.startsWith("{") &&
        (t.contains("\"action\"") || t.contains("\"tool\""))
}

@Composable
fun ForgeScreen(
    host: String,
    token: String,
    onSaveConfig: (String, String) -> Unit = { _, _ -> },
    model: ForgeViewModel = viewModel(key = "forge|$host|$token")
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // v1.6.14 (JAG-67): follow new messages only while the user is already at the
    // bottom (like every modern chat). If they scrolled up, we stop yanking the
    // view and instead show a "jump to latest" pill.
    // v1.6.19 (JAG-74): the old check only required the last item to be PARTIALLY
    // visible, so during streaming it stayed true and the pill vanished exactly
    // when it was needed. "At the bottom" now means the last item is FULLY visible.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf info.totalItemsCount == 0
            last.index >= info.totalItemsCount - 1 &&
                (last.offset + last.size) <= info.viewportEndOffset + 8
        }
    }
    // Follow on content growth too: streaming appends to the last message without
    // changing the item count, so keying only on `size` froze the view.
    val lastLen = model.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(model.messages.size, lastLen, model.liveThinking.length) {
        if (model.messages.isNotEmpty() && atBottom) {
            listState.animateToBottom()  // JAG-101: true bottom, not the last item's top
        }
    }
    // v1.6.26 (JAG-79): a new inline approval card must be SEEN — scroll to it even
    // if the user had scrolled away (the old design pinned the banner above the
    // input for exactly this reason).
    LaunchedEffect(model.approvalPing) {
        if (model.approvalPing > 0 && model.messages.isNotEmpty()) {
            listState.animateToBottom()  // JAG-101: the approval card is the last item
        }
    }
    // v1.6.4 (JAG-57): saveable across recomposition/rotation.
    var chatInput by rememberSaveable { mutableStateOf("") }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    // v1.6.11 (JAG-62): the tapped tool card opens a lateral detail drawer instead
    // of expanding inline, so the transcript stays compact.
    var selectedTool by remember { mutableStateOf<ForgeToolCard?>(null) }

    LaunchedEffect(host, token) { model.bind(context, host, token) }

    // v1.6.6 (JAG-58c): keep the pending-approval queue fresh for the whole
    // lifetime of the FORGE screen, not only while a run is streaming, so a
    // `required` tool always surfaces its Approve/Deny banner.
    LaunchedEffect(host, token) {
        while (true) {
            model.refreshApprovals()
            delay(3000)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        // ── header: run banner + plan status ──
        Column(Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "⚡ SPARKFORGE",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        style = TextStyle(brush = Brush.linearGradient(listOf(FAccent, FViolet, FYou)))
                    )
                    Text(
                        "harness · DGX Spark",
                        color = FTextMuted, fontSize = 10.sp, letterSpacing = 1.sp
                    )
                }
                val state = when {
                    model.coldStart != null -> "LLM AVVIO" to FAmber
                    model.busy -> "LIVE" to FMint
                    else -> "IDLE" to FTextMuted
                }
                Text(
                    state.first,
                    color = state.second, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(state.second.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Button(
                    onClick = { showConfig = !showConfig },
                    modifier = Modifier.padding(start = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FPanelRaised, contentColor = FTextMain)
                ) { Text("⚙", fontSize = 12.sp) }
            }
            // v1.6.20: show the running BUILD identity so it is never ambiguous
            // which app version (and which server) you are looking at.
            Text(
                "app v" + BuildConfig.VERSION_NAME +
                    (if (model.serverVersion.isNotBlank()) " · server v" + model.serverVersion
                     else " · server ?"),
                color = FTextMuted, fontSize = 10.sp, letterSpacing = 1.sp
            )
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

        // ── v1.6.13 (JAG-64): the panels rail is PINNED here — it must NEVER
        // scroll away. Only the transcript + agent trace below scroll. The
        // freccetta collapses the panel CONTENT, not the bar itself.
        ForgeToolbar(model)

        // ── scrollable page: transcript + agent trace ──
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // ── transcript (main content) ──
            Box(Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 460.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (model.messages.isEmpty()) {
                    item {
                        Text(
                            "Nessun messaggio. Scrivi qui sotto per parlare con SparkForge.",
                            color = FTextMuted, fontSize = 12.sp
                        )
                    }
                }
                // v1.6.3 (JAG-55): the transcript is selectable, so messages can be
                // copied from the phone (long-press → copy) like in any chat app.
                // Tool-call mini-cards live in the same list, so they appear in the
                // exact spot of the turn where the tool actually ran.
                // v1.6.30 (JAG-89): stable key — a tool card inserted before the
                // streaming bubble no longer shifts per-bubble UI state around.
                items(model.messages, key = { it.id }) { message ->
                    val card = message.tool
                    val ap = message.approval
                    when {
                        // v1.6.26 (JAG-79): approvals live IN the transcript, so an
                        // approval-gated tool is decided right where it happened.
                        ap != null -> ForgeApprovalView(
                            ap,
                            onApprove = { model.decideApproval(ap.id, "approve") },
                            onDeny = { model.decideApproval(ap.id, "deny") }
                        )
                        card != null -> ForgeToolCardView(card, onOpen = { selectedTool = card })
                        else -> SelectionContainer { ForgeBubble(message) }
                    }
                }
                if (model.liveThinking.isNotBlank()) {
                    item {
                        Text(
                            "🧠 ${model.liveThinking.takeLast(600)}",
                            color = FViolet, fontSize = 11.sp
                        )
                    }
                }
            }
            }
        }

        // v1.6.26 (JAG-79): the standalone "pending approvals" banner was removed —
        // approvals are now rendered INLINE in the transcript (single surface).

        // ── chat bar (JAG-78: the ONLY entry point — the separate agent bar is
        //    gone; `/goal` runs the agentic loop inside this same chat) ──
        Row(
            Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                label = { Text("Messaggio · /goal · /help") },
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
        // ── v1.6.22 (JAG-77): PERSISTENT "jump to latest" pill ──
        // Anchored to the SCREEN (not the scrolling transcript), so it never
        // vanishes when the user taps elsewhere or while the model streams.
        // Bright when scrolled away from the last message, muted when at bottom.
        if (model.messages.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 84.dp)
                    .background(if (atBottom) FPanelRaised else FMint, RoundedCornerShape(999.dp))
                    .clickable {
                        scope.launch { listState.animateToBottom() }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("⌄ fondo", color = if (atBottom) FTextMuted else FInk,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        // v1.6.11 (JAG-62): lateral detail drawer overlay (right edge, arrow to close).
        ForgeToolDetailDrawer(card = selectedTool, onClose = { selectedTool = null })
    }
}

/**
 * v1.6.35 (JAG-101): scroll to the true END of the transcript, not to the last
 * message. `animateScrollToItem(last)` parks a TALL final message with its TOP at
 * the viewport top, so the newest text stayed below the fold; the pill "⌄ ultimo"
 * jumped to the last message, not to the bottom of the chat. Adding a large offset
 * makes the list clamp to its maximum scroll (the real bottom); the loop is a
 * defensive fallback for a single pass that does not settle.
 */
private const val SCROLL_TO_END_OFFSET = 100_000_000

private suspend fun LazyListState.animateToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    var guard = 0
    while (canScrollForward && guard++ < 12) {
        animateScrollToItem(layoutInfo.totalItemsCount - 1, SCROLL_TO_END_OFFSET)
    }
}

@Composable
private fun ForgeAgentTrace(model: ForgeViewModel) {
    Column(
        Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).clickable { model.toggleTrace() }
            ) {
                Text(
                    (if (model.traceOpen) "▾ " else "▸ ") + "AGENTE · TRACE LIVE",
                    color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
        if (model.traceOpen) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(model.agentLines) { line ->
                    Text(line, color = FTextMuted, fontSize = 11.sp)
                }
            }
        }
        // v1.6.5 (JAG-58c): approvals are surfaced by the always-visible
        // banner above the input bars, not inside this collapsible trace.
        model.approvalNotice?.let {
            Text(it, color = FTextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
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
            color = if (isYou) FYou else FTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        val bubbleBg = when (message.role) {
            "system" -> Color.Transparent
            "you" -> FYou.copy(alpha = 0.14f)
            else -> FPanelRaised
        }
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .background(bubbleBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // v1.6.3 (JAG-55): the REPLY is the primary text of the bubble;
                // the reasoning stays in the CoT drawer (a per-message collapsed
                // "CoT" toggle keeps it reachable without replacing the answer).
                val clipboard = LocalClipboardManager.current
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
                // v1.6.3 (JAG-55): explicit copy action so a message can be taken
                // off the phone even when long-press selection is fiddly.
                val hasThinking = !message.thinking.isNullOrBlank()
                val canCopy = body.isNotEmpty() && !message.streaming && message.role != "system"
                // hoisted out of the conditional: a `remember` inside a branch
                // would desync the slot table when CoT appears mid-stream.
                // v1.6.30 (JAG-89): keyed on the stable `id`, NOT on `message` —
                // during streaming every chat.delta `copy()`s the message, so
                // remember(message) reset the expander and the chevron closed
                // itself while the agent was still thinking.
                var cotExpanded by remember(message.id) { mutableStateOf(false) }
                if (canCopy || hasThinking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = if (body.isNotEmpty()) 4.dp else 0.dp)
                    ) {
                        if (canCopy) {
                            Text(
                                "⧉ copia", color = FBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(message.text))
                                }
                            )
                        }
                        if (hasThinking) {
                            Text(
                                (if (cotExpanded) "▾ " else "▸ ") + "🧠 CoT",
                                color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { cotExpanded = !cotExpanded }
                            )
                        }
                    }
                    if (hasThinking && cotExpanded) {
                        Text(
                            message.thinking.orEmpty(), color = FTextMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** v1.6.3 (JAG-55): inline mini-card for a tool call/result, coherent with the run graph.
 *  v1.6.11 (JAG-62): stays compact; a tap opens the lateral detail drawer. */
@Composable
private fun ForgeToolCardView(card: ForgeToolCard, onOpen: () -> Unit) {
    val hasDetail = card.args.isNotBlank() || card.result.isNotBlank() || card.error.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(FPanelRaised, RoundedCornerShape(10.dp))
            .clickable(enabled = hasDetail) { onOpen() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when {
                card.ok == null -> "🔧"
                card.ok -> "✅"
                else -> "⛔"
            },
            fontSize = 12.sp
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                "tool · " + card.tool + (card.exitCode?.let { " · exit $it" } ?: ""),
                color = FTextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            val detail = card.summary.ifBlank { card.result }
            if (detail.isNotBlank()) {
                Text(
                    detail, color = FTextMuted, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        card.backend?.let {
            Text(it, color = FBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (hasDetail) {
            Text("›", color = FTextMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * v1.6.26 (JAG-79): an approval-gated tool call, rendered INLINE in the chat
 * transcript (replaces the old banner above the input). While pending it offers
 * APPROVA/NEGA; once decided (or expired) it collapses to a one-line note.
 */
@Composable
private fun ForgeApprovalView(
    ap: ForgeApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val pending = ap.status == "pending"
    Column(
        Modifier
            .fillMaxWidth()
            .background(FAmber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (pending) "🛡 approvazione · ${ap.tool} · #${ap.id}"
            else "🛡 ${ap.status} · ${ap.tool}",
            color = FAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold
        )
        if (ap.summary.isNotBlank()) {
            Text(
                ap.summary, color = FTextMain, fontSize = 11.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = FMint, contentColor = FInk)
                ) { Text("APPROVA", fontSize = 11.sp) }
                Button(
                    onClick = onDeny,
                    colors = ButtonDefaults.buttonColors(containerColor = FCoral, contentColor = FInk)
                ) { Text("NEGA", fontSize = 11.sp) }
            }
        }
    }
}

/** v1.6.11 (JAG-62): lateral drawer with a tool call's input/output/error.
 *  Slides in from the right edge; closes with the › arrow or a scrim tap. */
@Composable
private fun ForgeToolDetailDrawer(card: ForgeToolCard?, onClose: () -> Unit) {
    // Keep the last shown card so the exit animation still has content to render.
    var shown by remember { mutableStateOf<ForgeToolCard?>(null) }
    if (card != null) shown = card
    AnimatedVisibility(
        visible = card != null,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Box(Modifier.fillMaxSize()) {
            // scrim — tap anywhere outside the panel to close
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onClose() }
            )
            val c = shown
            if (c != null) {
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .widthIn(max = 340.dp)
                        .fillMaxWidth(0.88f)
                        .background(FPanel)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                c.ok == null -> "🔧"
                                c.ok -> "✅"
                                else -> "⛔"
                            },
                            fontSize = 13.sp
                        )
                        Text(
                            "tool · " + c.tool + (c.exitCode?.let { " · exit $it" } ?: ""),
                            color = FTextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        Box(Modifier.clickable { onClose() }.padding(6.dp)) {
                            Text("›", color = FTextMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    c.backend?.let {
                        Text(it, color = FBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (c.args.isNotBlank()) {
                        ToolFieldLabel("input")
                        SelectionContainer { Text(c.args, color = FTextMain, fontSize = 10.sp) }
                    }
                    if (c.result.isNotBlank()) {
                        ToolFieldLabel("output")
                        SelectionContainer { Text(c.result, color = FTextMain, fontSize = 10.sp) }
                    }
                    if (c.error.isNotBlank()) {
                        ToolFieldLabel("error")
                        SelectionContainer { Text(c.error, color = FCoral, fontSize = 10.sp) }
                    }
                    if (c.args.isBlank() && c.result.isBlank() && c.error.isBlank()) {
                        Text("nessun dettaglio disponibile", color = FTextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolFieldLabel(name: String) {
    Text(
        name.uppercase(),
        color = FTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}
