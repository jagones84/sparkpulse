package com.jagones.sparkpulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One live feed tick rendered in the Command tab. */
data class CommandFeedEvent(val id: Long, val kind: String, val text: String)

/** One approval waiting for a human decision. */
data class PendingApproval(val id: String, val tool: String, val summary: String, val runId: String?)

/**
 * Command-tab state: durable SSE feed tail (reconnect resumes from the last id),
 * live approvals and the optional voice path (STT in, TTS out).
 */
class CommandViewModel : ViewModel() {
    private val rest = ForgeRest()
    private var feedJob: Job? = null
    private var feedSse: SseClient? = null
    private var boundHost: String? = null
    private var boundToken: String? = null

    var feedEvents by mutableStateOf(listOf<CommandFeedEvent>())
        private set
    var feedStatus by mutableStateOf("OFFLINE")
        private set
    var approvals by mutableStateOf(listOf<PendingApproval>())
        private set
    var approvalMessage by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastFeedId by mutableStateOf(0L)
        private set
    var voiceText by mutableStateOf("")
    var voiceStatus by mutableStateOf<String?>(null)
        private set
    var commandReply by mutableStateOf<String?>(null)
        private set
    var ttsFile by mutableStateOf<String?>(null)
        private set
    var lastCommand by mutableStateOf("")
        private set

    /** Idempotent: connects the durable feed tail and refreshes the approval queue. */
    fun bind(host: String, token: String) {
        if (boundHost == host && boundToken == token && feedJob?.isActive == true) return
        feedJob?.cancel()
        feedSse?.close()
        boundHost = host
        boundToken = token
        refreshApprovals()
        feedJob = viewModelScope.launch {
            var lastId = withContext(Dispatchers.IO) {
                runCatching { rest.latestFeedId(host, token) }.getOrDefault(0L)
            }
            lastFeedId = lastId
            var backoff = 1_000L
            while (isActive) {
                feedStatus = "CONNECT"
                val channel = Channel<SseEvent>(Channel.UNLIMITED)
                val sse = SseClient()
                feedSse = sse
                launch(Dispatchers.IO) {
                    try {
                        sse.stream(
                            host, "/api/feed?since=$lastId", token,
                            onOpen = { channel.trySend(SseEvent(null, "__open__", "")) },
                            onEvent = { channel.trySend(it) }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        channel.trySend(SseEvent(null, "__closed__", e.message ?: "rete non disponibile"))
                    }
                    channel.close()
                }
                var received = false
                for (event in channel) {
                    when (event.type) {
                        "__open__" -> feedStatus = "LIVE"
                        "__closed__" -> error = "Feed: ${event.data.take(120)}"
                        "backlog" -> {
                            handleBacklog(event)
                            received = true
                            feedStatus = "LIVE"
                        }
                        else -> {
                            handleFeedEvent(event)
                            received = true
                            feedStatus = "LIVE"
                        }
                    }
                }
                feedSse = null
                if (received) backoff = 1_000L
                if (!isActive) break
                // Durable replay: resume the tail exactly after the last id seen.
                lastId = lastFeedId
                feedStatus = "RECONNECT"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun handleBacklog(event: SseEvent) {
        val array = runCatching { org.json.JSONArray(event.data) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            accumulate(item.optLong("id", 0L), item.optString("kind"), item)
        }
    }

    private fun handleFeedEvent(event: SseEvent) {
        val json = event.json() ?: JSONObject()
        val id = event.id?.toLongOrNull() ?: json.optLong("id", 0L)
        accumulate(id, event.type, json)
        if (event.type.startsWith("approval") || event.type == "tool.blocked") refreshApprovals()
    }

    /** Coalesces streaming token/think ticks so the feed stays readable. */
    private fun accumulate(id: Long, kind: String, data: JSONObject) {
        if (id > lastFeedId) lastFeedId = id
        val text = summarize(kind, data)
        if (text.isEmpty()) return
        val deltaKind = kind == "chat.delta" || kind == "agent.think"
        val last = feedEvents.lastOrNull()
        feedEvents = if (deltaKind && last != null && last.kind == kind) {
            feedEvents.dropLast(1) + last.copy(id = id, text = (last.text + text).takeLast(2_000))
        } else {
            (feedEvents + CommandFeedEvent(id, kind, text.take(500))).takeLast(300)
        }
    }

    private fun summarize(kind: String, data: JSONObject): String = when (kind) {
        "chat.user" -> "TU: " + data.optString("text")
        "chat.delta", "agent.think" -> data.optString("text")
        "agent.start" -> "▶ " + data.optString("goal")
        "agent.thought" -> "🧠 " + data.optString("thought")
        "agent.observation" -> "👁 " + data.optString("observation")
        "agent.finish" -> "🏁 " + data.optString("summary")
        "approval.request" -> "🛡 approvazione · ${data.optString("tool")} · #${data.optString("id")}"
        "approval.resolved" -> "🛡 ${data.optString("status")} · #${data.optString("id")}"
        "approval.decided" -> "🛡 ${data.optString("decision")} · #${data.optString("id")}"
        "tool.call" -> "🔧 ${data.optString("tool")}"
        "tool.result" -> "✅ ${data.optString("tool")} ok=${data.optBoolean("ok")}"
        "tool.blocked" -> "⛔ ${data.optString("tool")} · ${data.optString("reason")}"
        "voice.stt" -> "🎤 STT ${data.optInt("chars")} caratteri"
        "voice.tts" -> "🔊 TTS ${data.optInt("chars")} caratteri"
        else -> ""
    }

    fun refreshApprovals() {
        val host = boundHost ?: return
        val token = boundToken ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { parseApprovals(rest.text(host, token, "/api/approvals?status=pending&limit=50")) }
                    .getOrDefault(emptyList())
            }
            approvals = list
        }
    }

    fun decide(id: String, decision: String) {
        val host = boundHost ?: return
        val token = boundToken ?: return
        approvalMessage = if (decision == "approve") "Invio approvazione…" else "Invio rifiuto…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(
                        host, token, "/api/approvals/$id", "POST",
                        jsonBody = JSONObject().put("decision", decision).put("by", "android").toString()
                    )
                }
            }
            approvalMessage = result.fold(
                onSuccess = { decision + " inviato · #$id" },
                onFailure = { "Errore: ${it.message?.take(120)}" }
            )
            refreshApprovals()
        }
    }

    fun transcribe(wav: ByteArray) {
        val host = boundHost ?: return
        val token = boundToken ?: return
        if (wav.isEmpty()) {
            voiceStatus = "Registrazione vuota"
            return
        }
        voiceStatus = "Trascrivo…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token, "/api/voice/stt", "POST", rawBody = wav, contentType = "audio/wav")
                }
            }
            voiceStatus = result.fold(
                onSuccess = {
                    val text = runCatching { JSONObject(it).optString("text") }.getOrDefault("")
                    if (text.isBlank()) {
                        val err = runCatching { JSONObject(it).optString("error") }.getOrDefault("")
                        "STT senza testo${err.takeIf { e -> e.isNotEmpty() }?.let { e -> ": $e" } ?: ""}"
                    } else {
                        voiceText = text
                        "Trascrizione pronta"
                    }
                },
                onFailure = { "STT fallito: ${it.message?.take(120)}" }
            )
        }
    }

    /** Sends the recognised text to the harness as a chat command. */
    fun sendCommand(text: String) {
        val host = boundHost ?: return
        val token = boundToken ?: return
        if (text.isBlank()) return
        lastCommand = text
        commandReply = null
        voiceStatus = "Invio comando…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(
                        host, token, "/api/chat", "POST",
                        jsonBody = JSONObject().put("message", text).toString()
                    )
                }
            }
            result.fold(
                onSuccess = {
                    val json = runCatching { JSONObject(it) }.getOrNull()
                    commandReply = json?.optString("reply")?.takeIf { r -> r.isNotBlank() }
                        ?: json?.optString("error")
                        ?: it.take(300)
                    voiceStatus = null
                },
                onFailure = { voiceStatus = "Comando fallito: ${it.message?.take(120)}" }
            )
        }
    }

    fun speak(text: String) {
        val host = boundHost ?: return
        val token = boundToken ?: return
        if (text.isBlank()) return
        voiceStatus = "Sintesi vocale…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    rest.text(host, token, "/api/voice/tts", "POST", jsonBody = JSONObject().put("text", text).toString())
                }
            }
            result.fold(
                onSuccess = {
                    val json = runCatching { JSONObject(it) }.getOrNull()
                    val file = json?.optString("file").orEmpty()
                    if (file.isNotEmpty()) {
                        ttsFile = file
                        voiceStatus = null
                    } else {
                        voiceStatus = "TTS: " + (json?.optString("error").orEmpty().ifEmpty { "risposta vuota" })
                    }
                },
                onFailure = { voiceStatus = "TTS fallito: ${it.message?.take(120)}" }
            )
        }
    }

    override fun onCleared() {
        feedSse?.close()
        feedJob?.cancel()
        super.onCleared()
    }

    private fun parseApprovals(body: String): List<PendingApproval> {
        val array = JSONObject(body).optJSONArray("approvals") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            PendingApproval(
                id = item.optString("id"),
                tool = item.optString("tool"),
                summary = item.optString("summary").ifEmpty { item.optString("reason") },
                runId = item.optString("run_id").takeIf { it.isNotEmpty() }
            )
        }
    }
}

@Composable
fun CommandScreen(
    host: String,
    token: String,
    onSaveConfig: (String, String) -> Unit,
    model: CommandViewModel = viewModel(key = "command|$host|$token")
) {
    val context = LocalContext.current
    LaunchedEffect(host, token) { model.bind(host, token) }
    var hostInput by remember(host) { mutableStateOf(host) }
    var tokenInput by remember(token) { mutableStateOf(token) }
    var showConfig by remember { mutableStateOf(false) }

    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recording by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) model.transcribe(ByteArray(0)) }

    LaunchedEffect(model.ttsFile) {
        val file = model.ttsFile ?: return@LaunchedEffect
        playTts(context, host, token, file)
    }

    Column(Modifier.fillMaxSize()) {
        // ── header + status ──
        Column(Modifier.fillMaxWidth().background(FPanel).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "COMMAND · FEED LIVE",
                        color = FTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    Text("ultimo id ${model.lastFeedId}", color = FTextMuted, fontSize = 10.sp)
                }
                Text(
                    model.feedStatus,
                    color = if (model.feedStatus == "LIVE") FMint else FAmber,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { showConfig = !showConfig },
                    modifier = Modifier.padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FPanelRaised, contentColor = FTextMain)
                ) { Text("⚙", fontSize = 12.sp) }
            }
            if (showConfig) {
                OutlinedTextField(
                    value = hostInput, onValueChange = { hostInput = it },
                    label = { Text("Host SparkForge") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text("Token (v1.3 config)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Button(
                    onClick = { onSaveConfig(hostInput.trim(), tokenInput.trim()); showConfig = false },
                    modifier = Modifier.padding(top = 6.dp)
                ) { Text("SALVA") }
            }
            model.error?.let { Text(it, color = FCoral, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            // ── approvals ──
            SectionLabel("APPROVAZIONI · ${model.approvals.size} PENDING")
            if (model.approvals.isEmpty()) {
                Text("Nessuna approvazione in attesa", color = FTextMuted, fontSize = 12.sp)
            } else {
                model.approvals.forEach { approval ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(FPanelRaised, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                approval.tool, color = FAmber, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp)
                            )
                            Text(
                                "#${approval.id}", color = FTextMuted, fontSize = 10.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            approval.summary, color = FTextMain, fontSize = 12.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { model.decide(approval.id, "approve") },
                                colors = ButtonDefaults.buttonColors(containerColor = FMint, contentColor = FInk)
                            ) { Text("APPROVA", fontSize = 11.sp) }
                            Button(
                                onClick = { model.decide(approval.id, "deny") },
                                colors = ButtonDefaults.buttonColors(containerColor = FCoral, contentColor = FInk)
                            ) { Text("RIFIUTA", fontSize = 11.sp) }
                        }
                    }
                }
            }
            model.approvalMessage?.let { Text(it, color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }

            // ── voice ──
            SectionLabel("VOCE")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (recording) {
                            val bytes = recorder?.stop() ?: ByteArray(0)
                            recorder = null
                            recording = false
                            model.transcribe(bytes)
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            recorder = WavRecorder().also { it.start() }
                            recording = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) FCoral else FPanelRaised,
                        contentColor = if (recording) FInk else FTextMain
                    )
                ) { Text(if (recording) "⏹ STOP" else "🎤 REGISTRA", fontSize = 11.sp) }
                model.voiceStatus?.let { Text(it, color = FAmber, fontSize = 11.sp) }
            }
            OutlinedTextField(
                value = model.voiceText,
                onValueChange = { model.voiceText = it },
                label = { Text("Testo riconosciuto → comando") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                maxLines = 3
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { model.sendCommand(model.voiceText) },
                    enabled = model.voiceText.isNotBlank()
                ) { Text("INVIA COMANDO", fontSize = 11.sp) }
                Button(
                    onClick = { model.commandReply?.let { model.speak(it) } },
                    enabled = !model.commandReply.isNullOrBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = FBlue, contentColor = FInk)
                ) { Text("🔊 ASCOLTA", fontSize = 11.sp) }
            }
            model.commandReply?.let {
                Text("↳ $it", color = FTextMain, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }

            // ── feed ──
            SectionLabel("FEED · EVENTI (${model.feedEvents.size})")
        }

        LazyColumn(
            modifier = Modifier.heightIn(min = 120.dp, max = 260.dp).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(model.feedEvents) { event ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "#${event.id} ", color = FTextMuted, fontSize = 9.sp,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        "${event.kind}  ",
                        color = FBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        event.text, color = FTextMuted, fontSize = 10.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.stop()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = FTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** Plays a harness-generated wav (from /api/voice/audio/<file>). */
private fun playTts(context: Context, host: String, token: String, file: String) {
    val url = "http://${normalizeForgeHost(host)}:$FORGE_PORT/api/voice/audio/$file" +
        if (token.isBlank()) "" else "?token=" + URLEncoder.encode(token, "UTF-8")
    val player = MediaPlayer()
    player.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    )
    player.setOnCompletionListener { it.release() }
    player.setOnErrorListener { mp, _, _ -> mp.release(); true }
    runCatching {
        player.setDataSource(url)
        player.setOnPreparedListener { it.start() }
        player.prepareAsync()
    }.onFailure { player.release() }
}

/**
 * Records 16 kHz mono PCM16 to memory and wraps it in a minimal WAV container,
 * matching what POST /api/voice/stt expects (raw audio/wav body).
 */
private class WavRecorder(private val sampleRate: Int = 16_000) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = java.io.ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minimum > 0) minimum * 2 else 4_096
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        record = audioRecord
        audioRecord.startRecording()
        thread = Thread {
            val buffer = ByteArray(bufferSize)
            while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) pcm.write(buffer, 0, read)
            }
        }.also { it.start() }
    }

    fun stop(): ByteArray {
        val audioRecord = record
        if (audioRecord == null) return ByteArray(0)
        runCatching { audioRecord.stop() }
        runCatching { thread?.join(1_000) }
        runCatching { audioRecord.release() }
        record = null
        return wrapWav(pcm.toByteArray())
    }

    private fun wrapWav(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun le32(value: Int) = byteArrayOf(
            (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte()
        )
        fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + data.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1)) // PCM
        out.write(le16(1)) // mono
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2)) // byte rate
        out.write(le16(2)) // block align
        out.write(le16(16)) // bits per sample
        out.write("data".toByteArray())
        out.write(le32(data.size))
        out.write(data)
        return out.toByteArray()
    }
}
