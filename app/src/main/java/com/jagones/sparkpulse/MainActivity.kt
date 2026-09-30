package com.jagones.sparkpulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0A0E14)
private val Panel = Color(0xFF141B24)
private val PanelRaised = Color(0xFF1B2531)
private val TextMain = Color(0xFFF0F4F8)
private val TextMuted = Color(0xFF9AA8B7)
private val Mint = Color(0xFF57E3B1)
private val Amber = Color(0xFFFFC66D)
private val Coral = Color(0xFFFF7777)
private val Blue = Color(0xFF8DB8FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 14, 20)
        window.navigationBarColor = android.graphics.Color.rgb(10, 14, 20)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Mint)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    SparkPulseRoot()
                }
            }
        }
    }
}

@Composable
private fun SparkPulseRoot() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ForgeTabBar(tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                1 -> ForgeScreen(
                    host = ForgeConfig.host(context),
                    token = ForgeConfig.token(context),
                    onSaveConfig = { host, token -> ForgeConfig.save(context, host, token) }
                )
                2 -> CommandScreen(
                    host = ForgeConfig.host(context),
                    token = ForgeConfig.token(context),
                    onSaveConfig = { host, token -> ForgeConfig.save(context, host, token) }
                )
                else -> SparkPulseDashboard()
            }
        }
    }
}

@Composable
private fun ForgeTabBar(current: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf("PULSE", "⚡ FORGE", "COMMAND").forEachIndexed { index, label ->
            val selected = current == index
            Button(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Mint else PanelRaised,
                    contentColor = if (selected) Ink else TextMain
                )
            ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}
@Composable
private fun SparkPulseDashboard(model: StatusViewModel = viewModel()) {
    val state = model.state.value
    var hostInput by remember(state.host) { mutableStateOf(state.host) }
    var modelToConfirm by remember { mutableStateOf<ModelOption?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("SPARKPULSE", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("DGX Spark", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                ConnectionBadge(state.connection, state.demoMode)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("GPU UTIL", state.snapshot.gpu.utilization?.let { "${it.toInt()}%" } ?: "—", Mint, Modifier.weight(1f))
                MetricTile("TEMP", state.snapshot.gpu.temperatureC?.let { "${it.toInt()}°" } ?: "—", Amber, Modifier.weight(1f))
                MetricTile(
                    "GPU POWER",
                    state.snapshot.gpu.powerW?.let { "${it.toInt()}W" } ?: "—",
                    Color(0xFF8DB8FF),
                    Modifier.weight(1f),
                    state.snapshot.gpu.totalPowerW?.let { "${it.toInt()}W total" }
                )
            }
        }
        item {
            SectionCard(title = "MEMORIA UNIFICATA", trailing = state.snapshot.memory.unifiedUsedGb?.let { used ->
                state.snapshot.memory.unifiedTotalGb?.let { total -> "${used.oneDecimal()} / ${total.oneDecimal()} GB" }
            } ?: "DATI NON DISPONIBILI") {
                val used = state.snapshot.memory.unifiedUsedGb
                val total = state.snapshot.memory.unifiedTotalGb
                val fraction = if (used != null && total != null && total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(12.dp).background(PanelRaised, RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(12.dp).background(if (fraction > 0.85f) Coral else Mint, RoundedCornerShape(8.dp)))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SmallStat("RAM", state.snapshot.memory.ramUsedGb, state.snapshot.memory.ramTotalGb)
                    SmallStat("GPU", state.snapshot.memory.vramUsedGb, null)
                    Text("${(fraction * 100).toInt()}%", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            SectionCard(title = "MODELLO CARICATO", trailing = state.snapshot.model.vramGb?.let { "${it.oneDecimal()} GB" } ?: "") {
                Text(
                    text = when {
                        state.snapshot.model.loaded == false -> "Nessun modello attivo"
                        state.snapshot.model.name != null -> state.snapshot.model.name
                        state.snapshot.model.loaded == true -> "Modello attivo"
                        else -> "In attesa di dati"
                    },
                    color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (state.snapshot.model.pid != null) Text("PID ${state.snapshot.model.pid}", color = TextMuted, fontSize = 12.sp)
            }
        }
        item {
            SectionCard(title = "MODELLI", trailing = if (state.modelCommandInProgress) "SWITCH IN CORSO" else "") {
                if (state.models.isEmpty()) {
                    Text("Lista modelli non disponibile", color = TextMuted, fontSize = 13.sp)
                } else state.models.forEach { option ->
                    Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.demoMode && !state.modelCommandInProgress && !option.loaded) { modelToConfirm = option }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (option.loaded) "CARICATO" else "DISPONIBILE", color = if (option.loaded) Mint else TextMuted,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(78.dp))
                        Text(option.alias, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!option.loaded) Text("›", color = Mint, fontSize = 22.sp)
                    }
                }
                Button(onClick = model::refreshModels, enabled = !state.demoMode, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = TextMain)) {
                    Text("AGGIORNA MODELLI")
                }
                state.modelCommandMessage?.let {
                    Text(it, color = if (it.contains("non riuscito") || it.contains("Errore")) Coral else Mint,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            SectionCard(title = "TELEMETRIA · ULTIMI 5 MIN") {
                TelemetryChart("GPU UTIL", state.history, { it.gpuUtilization }, "%", Mint, 100.0)
                Spacer(Modifier.height(12.dp))
                TelemetryChart("GPU TEMP", state.history, { it.gpuTemperatureC }, "°C", Amber, null)
                Spacer(Modifier.height(12.dp))
                TelemetryChart("UMA USATA", state.history, { it.unifiedUsedGb }, "GB", Blue, null)
            }
        }
        item {
            SectionCard(title = "TOP PROCESSI", trailing = "${state.snapshot.topProcesses.size} / 3") {
                if (state.snapshot.topProcesses.isEmpty()) {
                    Text("Nessun processo GPU segnalato", color = TextMuted, fontSize = 14.sp)
                } else state.snapshot.topProcesses.forEachIndexed { index, process ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("0${index + 1}", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Text(process.name ?: "Processo senza nome", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(process.memoryGb?.let { "${it.oneDecimal()} GB" } ?: "—", color = TextMuted, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            SectionCard(title = "CONNESSIONE DGX") {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("Host o IP Tailscale") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.setHost(hostInput) })
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { model.setHost(hostInput) }, modifier = Modifier.weight(1f)) { Text("SALVA HOST") }
                    Button(
                        onClick = model::toggleDemoMode,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = TextMain)
                    ) { Text(if (state.demoMode) "ESCI DEMO" else "DEMO OFFLINE") }
                }
                Text("INTERVALLO POLLING", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1_000, 2_000, 5_000).forEach { interval ->
                        Button(
                            onClick = { model.setPollIntervalMillis(interval) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.pollIntervalMillis == interval.toLong()) Mint else PanelRaised,
                                contentColor = if (state.pollIntervalMillis == interval.toLong()) Ink else TextMain
                            )
                        ) { Text("${interval / 1_000} s") }
                    }
                }
            }
        }
        item {
            Text(
                text = when {
                    state.demoMode -> "DATI CAMPIONE · aggiornamento live sospeso · intervallo ${state.pollIntervalMillis / 1_000} s"
                    state.connection == ConnectionState.OK -> "LIVE · ${state.snapshot.host ?: state.host}${state.updatedAtMillis?.let { " · ${Date(it).clockTime()}" } ?: ""} · poll ogni ${state.pollIntervalMillis / 1_000} s"
                    state.connection == ConnectionState.RETRY -> "RICONNESSIONE · ultimo dato reale · retry ogni ${state.pollIntervalMillis / 1_000} s"
                    else -> "OFFLINE · ultimo dato reale${state.updatedAtMillis?.let { " · ${Date(it).clockTime()}" } ?: ""} · retry ogni ${state.pollIntervalMillis / 1_000} s"
                },
                color = TextMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }
    }
    modelToConfirm?.let { option ->
        AlertDialog(
            onDismissRequest = { modelToConfirm = null },
            title = { Text("Cambiare modello?") },
            text = { Text("Lo switch scarica il modello corrente e può richiedere ~60s. Continuare con ${option.alias}?") },
            confirmButton = {
                Button(onClick = {
                    modelToConfirm = null
                    model.switchModel(option.alias)
                }) { Text("CAMBIA MODELLO") }
            },
            dismissButton = { Button(onClick = { modelToConfirm = null }) { Text("ANNULLA") } },
            containerColor = Panel,
            titleContentColor = TextMain,
            textContentColor = TextMuted
        )
    }
}

@Composable
private fun TelemetryChart(
    title: String,
    history: List<TelemetryPoint>,
    value: (TelemetryPoint) -> Double?,
    unit: String,
    color: Color,
    fixedMaximum: Double?
) {
    val points = history.mapNotNull { sample -> value(sample)?.let { sample to it } }
    val current = points.lastOrNull()?.second
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Text(current?.let { "${it.oneDecimal()} $unit" } ?: "—", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = 5.dp)) {
            val left = 2f
            val right = size.width - 2f
            val top = 4f
            val bottom = size.height - 4f
            drawLine(PanelRaised, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
            if (points.size >= 2) {
                val minTime = points.first().first.timestampSeconds.toDouble()
                val timeSpan = (points.last().first.timestampSeconds - points.first().first.timestampSeconds).toDouble().coerceAtLeast(1.0)
                val maximum = fixedMaximum ?: (points.maxOf { it.second } * 1.15).coerceAtLeast(1.0)
                val path = Path()
                points.forEachIndexed { index, (sample, metric) ->
                    val x = left + ((sample.timestampSeconds - minTime) / timeSpan).toFloat() * (right - left)
                    val y = bottom - (metric / maximum).toFloat().coerceIn(0f, 1f) * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (points.isEmpty()) {
            Text("Dati non disponibili", color = TextMuted, fontSize = 10.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().first.timestampSeconds.clockTime(), color = TextMuted, fontSize = 9.sp)
                Text(points.last().first.timestampSeconds.clockTime(), color = TextMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(value, color = accent, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        if (detail != null) Text(detail, color = TextMuted, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SectionCard(title: String, trailing: String = "", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
            if (trailing.isNotEmpty()) Text(trailing, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun SmallStat(label: String, used: Double?, total: Double?) {
    Column {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = when {
                used == null -> "—"
                total != null -> "${used.oneDecimal()} / ${total.oneDecimal()}"
                else -> "${used.oneDecimal()} GB"
            },
            color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ConnectionBadge(connection: ConnectionState, demo: Boolean) {
    val tint = when {
        demo -> Amber
        connection == ConnectionState.OK -> Mint
        connection == ConnectionState.RETRY -> Amber
        else -> Coral
    }
    val label = when {
        demo -> "DEMO"
        connection == ConnectionState.OK -> "ONLINE"
        connection == ConnectionState.RETRY -> "RETRY"
        else -> "OFFLINE"
    }
    Row(
        modifier = Modifier.background(Panel, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
    }
}

private fun Double.oneDecimal(): String = String.format(Locale.ROOT, "%.1f", this)
private fun Date.clockTime(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(this)
private fun Long.clockTime(): String = Date(this * 1_000).clockTime()
