package com.jagones.sparkpulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    // v1.6.4 (JAG-57): rememberSaveable — these flags used to reset on
    // recomposition/rotation, so the SESSIONI menu vanished by itself.
    var sessionsOpen by rememberSaveable { mutableStateOf(false) }
    var compactOpen by rememberSaveable { mutableStateOf(false) }
    val graphTodo = model.graphNodes.count { it.status != "done" }

    Column(Modifier.fillMaxWidth().background(FPanelRaised).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForgeChip(if (sessionsOpen) "▾ SESSIONI" else "▸ SESSIONI", active = sessionsOpen) {
                sessionsOpen = !sessionsOpen
                if (sessionsOpen) model.refreshSessions()
            }
            ForgeChip(
                "🧩 GRAFO $graphTodo",
                active = model.graphOpen
            ) { model.toggleGraph() }
            ForgeChip("🗜 COMPACTION", active = compactOpen) {
                compactOpen = !compactOpen
                if (compactOpen) model.refreshContext()
            }
            ForgeChip("🧠 CoT", active = model.cotOpen) { model.toggleCot() }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ForgeChip("🧭 Dove sei", active = model.selfOpen) { model.toggleSelf() }
            Spacer(Modifier.weight(1f))
            // v1.6.3 (JAG-55): real values only. Without a session the /api/context
            // numbers are meaningless ("budget 6000 / 0 messages") → show n/d.
            val ctxReady = model.contextAvailable && model.contextBudget > 0
            Text(
                if (!ctxReady) "ctx n/d" else "ctx ${model.contextUsed}/${model.contextBudget}",
                color = if (ctxReady && model.contextOver) FCoral else FMint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        model.statusMessage?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = FBlue, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Box(Modifier.clickable { model.dismissStatus() }.padding(start = 6.dp)) {
                    Text("✕", color = FTextMuted, fontSize = 12.sp)
                }
            }
        }
        if (sessionsOpen) ForgeSessionsPanel(model) { sessionsOpen = false }
        if (model.graphOpen) ForgeGraphPanel(model) { model.toggleGraph() }
        if (compactOpen) ForgeCompactPanel(model) { compactOpen = false }
        if (model.cotOpen) ForgeCotDrawer(model)
        if (model.selfOpen) ForgeSelfPanel(model)
    }
}

/**
 * v1.6.3 — collapsible COMPACTION panel: the context meter plus the manual
 * "compact now" action. Folded away by default so the chat remains the focus.
 */
@Composable
private fun ForgeCompactPanel(model: ForgeViewModel, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "COMPACTION · contesto sessione", color = FMint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { onClose() }.padding(6.dp)) {
                Text("✕", color = FTextMuted, fontSize = 14.sp)
            }
        }
        val budget = if (model.contextBudget > 0) model.contextBudget else 0
        if (budget <= 0) {
            Text(
                "contesto n/d — nessuna sessione attiva (crea o seleziona una sessione)",
                color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            model.activeSession?.let {
                Text(
                    "sessione $it · ${model.contextMessages} messaggi",
                    color = FBlue, fontSize = 10.sp
                )
            }
            val pct = ((model.contextUsed.toFloat() / budget.coerceAtLeast(1)) * 100f).coerceIn(0f, 100f)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 8.dp).padding(top = 6.dp)
                    .background(FInk, RoundedCornerShape(999.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(pct / 100f).heightIn(min = 8.dp)
                        .background(if (model.contextOver) FCoral else FMint, RoundedCornerShape(999.dp))
                )
            }
            Text(
                "usati ${model.contextUsed} / $budget token" + if (model.contextOver) " · sopra soglia" else "",
                color = if (model.contextOver) FCoral else FTextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Button(
            onClick = { model.compactContext() },
            modifier = Modifier.padding(top = 6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("⇩ Compatta ora", fontSize = 11.sp) }
    }
}

/**
 * v1.6.3 — "Dove sei" panel. Closed by default; the toolbar chip toggles it and
 * the ✕ always closes it (previously it could not be dismissed). Shows the
 * `/api/selfcheck` outcome and the live status line of the `self` action.
 */
@Composable
private fun ForgeSelfPanel(model: ForgeViewModel) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DOVE SEI · self", color = FViolet, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { model.toggleSelf() }.padding(6.dp)) {
                Text("✕", color = FTextMuted, fontSize = 14.sp)
            }
        }
        model.selfCheck?.let { sc ->
            Text(
                (if (sc.ok) "✅ " else "⛔ ") + sc.title,
                color = if (sc.ok) FMint else FCoral, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)
            )
            Text(sc.detail, color = FTextMuted, fontSize = 11.sp)
        }
        if (model.selfChecking) {
            Text("selfcheck in corso…", color = FBlue, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        model.statusMessage?.let {
            Text(it, color = FBlue, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            SELF_GOAL,
            color = FTextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
        )
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
private fun ForgeSessionsPanel(model: ForgeViewModel, onClose: () -> Unit) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 280.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SESSIONI · /api/sessions", color = FTextMuted, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { onClose() }.padding(6.dp)) {
                Text("✕", color = FTextMuted, fontSize = 14.sp)
            }
        }
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

/**
 * v0.6 — the current run's LLM-generated task graph: streamed live via
 * `graph.node.added` / `graph.node.updated` (chat + agent + `/api/feed`) and
 * interactive — tap a node to reveal its deps and the evidence that justified
 * `done`. Bound to `GET /api/runs/<id>/graph`.
 */
@Composable
private fun ForgeGraphPanel(model: ForgeViewModel, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        val done = model.graphNodes.count { it.status == "done" }
        val runShort = model.graphRunId?.take(8) ?: "-"
        val total = model.graphNodes.size
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TASK GRAPH · run $runShort · $done/$total done",
                color = FTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, modifier = Modifier.weight(1f).padding(bottom = 4.dp)
            )
            Box(Modifier.clickable { onClose() }.padding(6.dp)) {
                Text("✕", color = FTextMuted, fontSize = 14.sp)
            }
        }
        GraphActionRow(model)
        if (model.graphNodes.isEmpty()) {
            Text(
                "Nessun grafo per il run corrente. Invia una richiesta: il modello genera " +
                    "il grafo (write_todos) e lo aggiorna live.",
                color = FTextMuted, fontSize = 11.sp
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(model.graphNodes) { n ->
                    val selected = model.selectedNode?.id == n.id
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) FPanelRaised else FInk, RoundedCornerShape(8.dp))
                            .clickable { model.selectNode(n) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val glyph = nodeGlyph(n.status)
                            val meta = buildString {
                                if (n.deps.isNotEmpty()) {
                                    append(n.deps.joinToString(",", prefix = " [", postfix = "]"))
                                }
                                if (n.evidence.isNotEmpty()) {
                                    if (isNotEmpty()) append(" ")
                                    append("ev=")
                                    append(n.evidence.size)
                                }
                            }
                            Text(glyph, color = nodeColor(n.status), fontSize = 12.sp)
                            Text(n.label, color = FTextMain, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            if (meta.isNotEmpty()) {
                                Text(meta, color = FTextMuted, fontSize = 9.sp)
                            }
                        }
                        if (selected) NodeDetail(n)
                    }
                }
            }
        }
    }
}

/**
 * v0.6 interactivity (task JAG-46 #3): mutate the *current run's* graph from the
 * phone — add an operator node, cancel the selected one, or ask the server to
 * re-plan. All three POST `/api/runs/<id>/graph/nodes`; the resulting
 * `graph.node.*` events update the list live.
 */
@Composable
private fun GraphActionRow(model: ForgeViewModel) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = model.graphDraft,
                onValueChange = { model.updateGraphDraft(it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("nuovo nodo…", fontSize = 11.sp) }
            )
            Button(
                onClick = { model.addGraphNode() },
                enabled = model.graphDraft.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("＋", fontSize = 13.sp) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { model.replanGraph() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            ) { Text("↻ Re-plan", fontSize = 10.sp) }
            model.selectedNode?.let { n ->
                Button(
                    onClick = { model.cancelGraphNode(n) },
                    colors = ButtonDefaults.buttonColors(containerColor = FCoral),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("✕ Annulla ${n.id}", fontSize = 10.sp) }
            }
        }
    }
}

/** Tap-to-detail: node id/status/deps plus the evidence attached to the node. */
@Composable
private fun NodeDetail(n: ForgeNode) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val depsPart = if (n.deps.isNotEmpty()) " · deps " + n.deps.joinToString(", ") else ""
        Text(
            n.id + " · " + n.status + depsPart,
            color = FTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold
        )
        Text(
            if (n.evidence.isEmpty()) "(nessuna evidenza: i nodi done ne richiedono una)"
            else n.evidence.joinToString("\n") { "• $it" },
            color = if (n.evidence.isEmpty()) FTextMuted else FTextMain, fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

private fun nodeGlyph(status: String) = when (status) {
    "done" -> "✓"
    "doing" -> "◐"
    "blocked" -> "⛔"
    "cancelled" -> "✕"
    else -> "○"
}

private fun nodeColor(status: String) = when (status) {
    "done" -> FMint
    "doing" -> FAmber
    "blocked" -> FCoral
    else -> FTextMuted
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
