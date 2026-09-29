package com.jagones.sparkpulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val FInk = Color(0xFF0A0E14)
private val FPanel = Color(0xFF141B24)
private val FPanelRaised = Color(0xFF1B2531)
private val FTextMain = Color(0xFFF0F4F8)
private val FTextMuted = Color(0xFF9AA8B7)
private val FMint = Color(0xFF57E3B1)
private val FBlue = Color(0xFF8DB8FF)
private val FAmber = Color(0xFFFFC66D)
private val FCoral = Color(0xFFFF7777)
private val FViolet = Color(0xFFB98BFF)

private const val FORGE_PORT = 8790

/** One chat exchange kept in the Forge screen transcript. */
data class ForgeMessage(
    val role: String, // "you" | "forge" | "system"
    val text: String,
    val thinking: String? = null
)

/** A step of the harness PLAN. */
data class ForgePlanStep(val title: String, val done: Boolean)

/** A task of the harness board. */
data class ForgeTask(val title: String, val status: String)

/** Result of one agent-loop run. */
data class ForgeAgentTrace(val lines: List<String>, val summary: String)

class ForgeClient {
    fun chat(host: String, message: String): ForgeMessage {
        val payload = JSONObject().put("message", message).toString()
        val body = request(host, "/api/chat", "POST", payload)
        val reply = body.optString("reply", "")
        val reasoning = body.optJSONArray("reasoning")?.let { arr ->
            buildString { for (i in 0 until arr.length()) append(arr.optString(i)) }.trim()
        } ?: body.optString("reasoning", "").trim().ifEmpty { null }
        return ForgeMessage("forge", reply, reasoning?.ifEmpty { null })
    }

    fun plan(host: String): Pair<String, List<ForgePlanStep>> {
        val body = request(host, "/api/plan", "GET")
        val goal = body.optString("goal", "")
        val steps = body.optJSONArray("steps")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.optJSONObject(i) ?: JSONObject()
                ForgePlanStep(s.optString("title", ""), s.optBoolean("done", false))
            }
        } ?: emptyList()
        return goal to steps
    }

    fun tasks(host: String): List<ForgeTask> {
        val body = request(host, "/api/tasks", "GET")
        return body.optJSONArray("tasks")?.let { arr ->
            (0 until arr.length()).map { i ->
                val t = arr.optJSONObject(i) ?: JSONObject()
                ForgeTask(t.optString("title", ""), t.optString("status", "todo"))
            }
        } ?: emptyList()
    }

    fun agentRun(host: String, goal: String, maxSteps: Int = 4): ForgeAgentTrace {
        val payload = JSONObject().put("goal", goal).put("max_steps", maxSteps).toString()
        val body = request(host, "/api/agent/run", "POST", payload)
        val lines = mutableListOf<String>()
        body.optJSONArray("trace")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val action = t.optString("action", "")
                val obs = t.optString("observation", "")
                val thought = t.optString("thought", "")
                when {
                    action == "finish" || obs.isEmpty() -> lines.add("🏁 ${t.optString("summary", "")}")
                    else -> {
                        if (thought.isNotBlank()) lines.add("🧠 $thought")
                        lines.add("⚡ $action → $obs")
                    }
                }
            }
        }
        return ForgeAgentTrace(lines, body.optString("model", ""))
    }

    private fun request(host: String, path: String, method: String, body: String? = null): JSONObject {
        val normalizedHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require(normalizedHost.isNotBlank()) { "Host is empty" }
        val connection = URL("http://$normalizedHost:$FORGE_PORT$path").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 300_000 // model load can take ~60s+
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            if (connection.responseCode !in 200..299) {
                val err = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                error("HTTP ${connection.responseCode}${err?.take(160)?.let { ": $it" } ?: ""}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

class ForgeViewModel : ViewModel() {
    private val client = ForgeClient()

    var messages by mutableStateOf(listOf(ForgeMessage("system", "Connesso a SparkForge :8790 — scrivi un messaggio o lancia l'agente.")))
        private set
    var planGoal by mutableStateOf("")
    var planSteps by mutableStateOf(listOf<ForgePlanStep>())
    var tasks by mutableStateOf(listOf<ForgeTask>())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun refresh(host: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val (goal, steps) = client.plan(host)
                    planGoal = goal; planSteps = steps
                    tasks = client.tasks(host)
                    error = null
                } catch (e: Exception) {
                    error = "Forge non raggiungibile: ${e.message?.take(120)}"
                }
            }
        }
    }

    fun send(host: String, text: String) {
        if (text.isBlank() || busy) return
        messages = messages + ForgeMessage("you", text)
        busy = true; error = null
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { client.chat(host, text) }
            }
            busy = false
            reply.fold(
                onSuccess = { messages = messages + it },
                onFailure = { error = "Chat fallita: ${it.message?.take(160)}" }
            )
        }
    }

    fun runAgent(host: String, goal: String) {
        if (goal.isBlank() || busy) return
        messages = messages + ForgeMessage("you", "⚡ agente: $goal")
        busy = true; error = null
        viewModelScope.launch {
            val trace = withContext(Dispatchers.IO) {
                runCatching { client.agentRun(host, goal) }
            }
            busy = false
            trace.fold(
                onSuccess = {
                    messages = messages + ForgeMessage("forge", it.lines.joinToString("\n"))
                    refresh(host)
                },
                onFailure = { error = "Agente fallito: ${it.message?.take(160)}" }
            )
        }
    }
}

@Composable
fun ForgeScreen(host: String, model: ForgeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    LaunchedEffect(host) { model.refresh(host) }
    val listState = rememberLazyListState()
    LaunchedEffect(model.messages.size) {
        if (model.messages.isNotEmpty()) listState.animateScrollToItem(model.messages.size - 1)
    }
    var chatInput by remember { mutableStateOf("") }
    var agentInput by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = darkColorScheme(background = FInk, surface = FPanel, primary = FMint)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // ── plan + tasks summary ──
            Column(
                Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("SPARKFORGE · COMANDO DGX", color = FTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                if (model.planGoal.isNotBlank()) {
                    Text(model.planGoal, color = FTextMain, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    val done = model.planSteps.count { it.done }
                    Text("PLAN · $done/${model.planSteps.size} step", color = FMint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                val open = model.tasks.count { it.status != "done" }
                Text(
                    if (open > 0) "TASKS · $open da fare" else "TASKS · tutto chiuso",
                    color = if (open > 0) FAmber else FMint, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                model.error?.let { Text(it, color = FCoral, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            }

            // ── transcript ──
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(model.messages) { msg ->
                    ForgeBubble(msg)
                }
                if (model.busy) {
                    item { Text("… SparkForge sta pensando", color = FTextMuted, fontSize = 12.sp, modifier = Modifier.padding(6.dp)) }
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
                    onClick = { model.runAgent(host, agentInput.trim()); agentInput = "" },
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
                Button(
                    onClick = { model.send(host, chatInput.trim()); chatInput = "" },
                    enabled = !model.busy && chatInput.isNotBlank()
                ) { Text("SEND") }
            }
        }
    }
}

@Composable
private fun ForgeBubble(msg: ForgeMessage) {
    val isYou = msg.role == "you"
    val accent = when {
        isYou -> FBlue
        msg.role == "system" -> FTextMuted
        else -> FMint
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (isYou) Alignment.End else Alignment.Start
    ) {
        Text(
            if (isYou) "TU" else if (msg.role == "system") "· · ·" else "SPARKFORGE",
            color = FTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .background(if (msg.role == "system") Color.Transparent else FPanel, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (!msg.thinking.isNullOrBlank()) {
                    Text("🧠 ragionamento", color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(msg.thinking, color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(msg.text, color = if (msg.role == "system") FTextMuted else FTextMain, fontSize = 13.sp)
            }
        }
    }
}
