package com.jagones.sparkpulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SparkPulse v1.5 — additive panels for the SparkForge v0.5 harness features:
 * session list/switch/create/delete, context token indicator + compaction,
 * live todo breakdown from the SSE feed, CoT (reasoning) drawer and the
 * self-knowledge quick action. Purely additive to the v1.4 FORGE tab.
 */
@Composable
fun ForgeToolbar(model: ForgeViewModel) {
    var sessionsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(FPanelRaised).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForgeChip(if (sessionsOpen) "▾ SESSIONI" else "▸ SESSIONI", active = sessionsOpen) {
                sessionsOpen = !sessionsOpen
                if (sessionsOpen) model.refreshSessions()
            }
            ForgeChip("TASKS ${model.tasks.count { it.status != "done" }}", active = tasksOpen) {
                tasksOpen = !tasksOpen
                if (tasksOpen) model.refreshTasks()
            }
            ForgeChip("🧠 CoT", active = model.cotOpen) { model.toggleCot() }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ForgeChip("🧭 Dove sei / comandi") { model.askSelf() }
            ForgeChip("⇩ Compatta contesto") { model.compactContext() }
            Spacer(Modifier.weight(1f))
            val budget = if (model.contextBudget > 0) model.contextBudget else 6000
            Text(
                "ctx ${model.contextUsed}/$budget",
                color = if (model.contextOver) FCoral else FMint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        model.statusMessage?.let {
            Text(it, color = FBlue, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        model.selfCheck?.let { sc ->
            Text(
                (if (sc.ok) "✅ " else "⛔ ") + sc.title + " · " + sc.detail,
                color = if (sc.ok) FMint else FCoral,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (sessionsOpen) ForgeSessionsPanel(model)
        if (tasksOpen) ForgeTasksPanel(model)
        if (model.cotOpen) ForgeCotDrawer(model)
    }
}

@Composable
private fun ForgeChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) FMint else FInk, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (active) FInk else FTextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Session list/switch/create/delete (`GET/POST/DELETE /api/sessions`, `GET /api/history`). */
@Composable
private fun ForgeSessionsPanel(model: ForgeViewModel) {
    var newTitle by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 280.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Text(
            "SESSIONI · /api/sessions", color = FTextMuted, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Nuova sessione") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { model.newSession(newTitle.trim()); newTitle = "" },
                colors = ButtonDefaults.buttonColors(containerColor = FMint, contentColor = FInk)
            ) { Text("+") }
        }
        if (model.sessions.isEmpty()) {
            Text("Nessuna sessione.", color = FTextMuted, fontSize = 11.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(model.sessions) { s -> SessionRow(s, s.id == model.activeSession, model) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: ForgeSession, active: Boolean, model: ForgeViewModel) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) FPanelRaised else FInk, RoundedCornerShape(8.dp))
            .clickable { model.switchSession(s.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (active) "● " else "") + s.title,
                color = if (active) FMint else FTextMain, fontSize = 12.sp
            )
            Text("${s.id} · ${s.messages} msg", color = FTextMuted, fontSize = 10.sp)
        }
        Box(Modifier.clickable { model.deleteSession(s.id) }.padding(6.dp)) {
            Text("🗑", fontSize = 14.sp)
        }
    }
}

/** Live todo breakdown, seeded by `/api/tasks` and kept fresh by the SSE feed. */
@Composable
private fun ForgeTasksPanel(model: ForgeViewModel) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 240.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Text(
            "TASKS · TODO BREAKDOWN (live)", color = FTextMuted, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp)
        )
        if (model.tasks.isEmpty()) {
            Text("Nessun task dal feed /api/tasks.", color = FTextMuted, fontSize = 11.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(model.tasks) { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(taskGlyph(t.status), color = taskColor(t.status), fontSize = 12.sp, modifier = Modifier.width(20.dp))
                        Text(t.title, color = FTextMain, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Expandable CoT drawer showing the `think` channel of the chat SSE stream. */
@Composable
private fun ForgeCotDrawer(model: ForgeViewModel) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxWidth().heightIn(max = 200.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Text(
            "CoT · RAGIONAMENTO (channel=think)", color = FViolet, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        LaunchedEffect(model.reasoning) {
            if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
        }
        Text(
            model.reasoning.ifBlank { "In attesa del reasoning dal server…" },
            color = if (model.reasoning.isBlank()) FTextMuted else FTextMain,
            fontSize = 11.sp,
            modifier = Modifier.verticalScroll(scroll).padding(top = 4.dp)
        )
    }
}

private fun taskGlyph(status: String) = when (status) {
    "done" -> "✓"
    "doing" -> "◐"
    else -> "○"
}

private fun taskColor(status: String) = when (status) {
    "done" -> FMint
    "doing" -> FAmber
    else -> FTextMuted
}

/**
 * SparkPulse v1.5.1 — FORGE settings panel: host + token plus a "Test
 * connessione" button that probes `GET /api/selfcheck` and reports the outcome
 * in green/red with an explicit reason (401 / timeout / DNS). Purely additive.
 */
@Composable
fun ForgeConfigPanel(
    host: String,
    token: String,
    model: ForgeViewModel,
    onSaveConfig: (String, String) -> Unit
) {
    var hostInput by remember(host) { mutableStateOf(host) }
    var tokenInput by remember(token) { mutableStateOf(token) }

    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Text(
            "IMPOSTAZIONI FORGE · host + token", color = FTextMuted, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        OutlinedTextField(
            value = hostInput, onValueChange = { hostInput = it },
            label = { Text("Host SparkForge (IP Tailscale DGX)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        OutlinedTextField(
            value = tokenInput, onValueChange = { tokenInput = it },
            label = { Text("Token FORGE") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSaveConfig(hostInput.trim(), tokenInput.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = FMint, contentColor = FInk)
            ) { Text("SALVA", fontSize = 11.sp) }
            Button(
                onClick = { model.testConnection(hostInput.trim(), tokenInput.trim()) },
                enabled = !model.selfChecking,
                colors = ButtonDefaults.buttonColors(containerColor = FPanelRaised, contentColor = FTextMain)
            ) { Text(if (model.selfChecking) "TEST…" else "TEST CONNESSIONE", fontSize = 11.sp) }
        }
        Text(
            "Test connessione = GET /api/selfcheck sull'host/porta :$FORGE_PORT in uso.",
            color = FTextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
        )
        model.selfCheck?.let { sc ->
            Text(
                (if (sc.ok) "✅ " else "⛔ ") + sc.title + " · " + sc.detail,
                color = if (sc.ok) FMint else FCoral, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
