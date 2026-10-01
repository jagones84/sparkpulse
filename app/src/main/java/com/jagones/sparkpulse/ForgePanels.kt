package com.jagones.sparkpulse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Checkbox
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
    // v1.6.11 (JAG-62): the chip row became a LATERAL collapsible bar — the
    // panels no longer push the transcript down; a freccetta expands/collapses
    // the whole side rail (nav column + panel).
    var railOpen by rememberSaveable { mutableStateOf(false) }
    var sessionsOpen by rememberSaveable { mutableStateOf(false) }
    var compactOpen by rememberSaveable { mutableStateOf(false) }
    val graphTodo = model.graphNodes.count { it.status != "done" }
    val ctxReady = model.contextAvailable && model.contextBudget > 0

    Column(Modifier.fillMaxWidth().background(FPanelRaised).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // ── top strip: the freccetta + the always-useful context meter ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForgeChip(if (railOpen) "‹ PANNELLI" else "› PANNELLI", active = railOpen) {
                railOpen = !railOpen
                if (railOpen) model.refreshSessions()
            }
            // v1.6.17 (JAG-71): one-tap access to the model picker; shows the
            // short name of the active model (or AUTO = server default).
            val mdName = model.selectedModel?.substringAfter(':')
                ?.let { if (it.length > 11) it.take(10) + "…" else it } ?: "AUTO"
            ForgeChip("🤖 $mdName", active = model.modelPickerOpen) { model.toggleModelPicker() }
            Spacer(Modifier.weight(1f))
            if (graphTodo > 0) {
                Text("🧩 $graphTodo", color = FViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            val pct = if (model.contextPct > 0f) " · ${model.contextPct.toInt()}%" else ""
            Text(
                if (!ctxReady) "ctx n/d" else
                    "ctx ${fmtTokens(model.contextUsed)}/${fmtTokens(model.contextBudget)}$pct",
                color = when {
                    !ctxReady -> FTextMuted
                    model.contextOver -> FCoral
                    model.contextNearLimit -> FAmber
                    else -> FMint
                },
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
        // v1.6.17 (JAG-71 fix): the MODEL picker lives OUTSIDE the lateral rail, so
        // tapping the 🤖 chip always shows it (before it only appeared when the
        // rail was already open → the chip looked dead).
        AnimatedVisibility(
            visible = model.modelPickerOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(top = 8.dp)) { ForgeModelPicker(model) }
        }
        // ── lateral collapsible bar: nav column on the left, panel on the right ──
        AnimatedVisibility(
            visible = railOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // JAG-78: the panels are MUTUALLY EXCLUSIVE (accordion). Before,
                // each had its own flag and the `when` below showed only the first
                // open one, so tapping another panel looked dead ("non si disattiva
                // l'altro, così non si vede"). Now opening one closes the others.
                fun closeOthers(keep: String) {
                    if (keep != "sessions") sessionsOpen = false
                    if (keep != "compact") compactOpen = false
                    if (keep != "graph" && model.graphOpen) model.toggleGraph()
                    if (keep != "cot" && model.cotOpen) model.toggleCot()
                    if (keep != "self" && model.selfOpen) model.toggleSelf()
                    if (keep != "settings" && model.settingsOpen) model.toggleSettings()
                }
                Column(Modifier.width(126.dp)) {
                    ForgeRailItem("SESSIONI", sessionsOpen) {
                        val on = !sessionsOpen
                        closeOthers("sessions")
                        sessionsOpen = on
                        if (on) model.refreshSessions()
                    }
                    ForgeRailItem("🧩 GRAFO $graphTodo", model.graphOpen) {
                        val on = !model.graphOpen
                        closeOthers("graph")
                        if (on != model.graphOpen) model.toggleGraph()
                    }
                    ForgeRailItem("🗜 COMPACTION", compactOpen) {
                        val on = !compactOpen
                        closeOthers("compact")
                        compactOpen = on
                        if (on) model.refreshContext()
                    }
                    ForgeRailItem("🧠 CoT", model.cotOpen) {
                        val on = !model.cotOpen
                        closeOthers("cot")
                        if (on != model.cotOpen) model.toggleCot()
                    }
                    ForgeRailItem("🧭 Dove sei", model.selfOpen) {
                        val on = !model.selfOpen
                        closeOthers("self")
                        if (on != model.selfOpen) model.toggleSelf()
                    }
                    ForgeRailItem("⚙ SETTINGS", model.settingsOpen) {
                        val on = !model.settingsOpen
                        closeOthers("settings")
                        if (on != model.settingsOpen) model.toggleSettings()
                    }
                }
                Column(Modifier.weight(1f)) {
                    when {
                        sessionsOpen -> ForgeSessionsPanel(model) { sessionsOpen = false }
                        model.graphOpen -> ForgeGraphPanel(model) { model.toggleGraph() }
                        compactOpen -> ForgeCompactPanel(model) { compactOpen = false }
                        model.cotOpen -> ForgeCotDrawer(model)
                        model.selfOpen -> ForgeSelfPanel(model)
                        model.settingsOpen -> ForgeSettingsPanel(model) { model.toggleSettings() }
                        else -> Text(
                            "Scegli un pannello a sinistra.",
                            color = FTextMuted, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/** v1.6.16 (JAG-70): compact token count for the ctx indicator (258048 → 258k). */
private fun fmtTokens(n: Int): String =
    if (n >= 10000) "%.0fk".format(n / 1000.0) else n.toString()

/** v1.6.11 (JAG-62): one row of the lateral rail nav column. */
@Composable
private fun ForgeRailItem(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (active) FPanel else FPanelRaised, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (active) FMint else FTextMain,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
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
                "usati ${fmtTokens(model.contextUsed)} / ${fmtTokens(budget)} token" +
                    (if (model.contextPct > 0f) " · ${model.contextPct.toInt()}%" else "") +
                    (if (model.contextNearLimit && !model.contextOver)
                        " · auto-compact al ${model.contextThreshold.toInt()}%" else "") +
                    (if (model.contextOver) " · sopra soglia" else ""),
                color = when {
                    model.contextOver -> FCoral
                    model.contextNearLimit -> FAmber
                    else -> FTextMuted
                }, fontSize = 11.sp,
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
 * v1.6.27 (JAG-81): SETTINGS — per-tool approval policy, right in the panels.
 * One row per tool with two checkboxes: AUTO (execute without asking) and ON
 * (tool enabled). Persisted server-side in config/tools.yaml via POST /api/tools,
 * so the agent stops blocking on tools you have explicitly trusted.
 */
@Composable
private fun ForgeSettingsPanel(model: ForgeViewModel, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 260.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SETTINGS · politiche dei tool", color = FBlue, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { onClose() }.padding(6.dp)) {
                Text("✕", color = FTextMuted, fontSize = 14.sp)
            }
        }
        Text(
            "AUTO = esegue senza chiedere approvazione · ON = tool abilitato",
            color = FTextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
        )
        model.settingsNotice?.let {
            Text(it, color = FMint, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    model.setToolPolicy("fs.write", auto = true)
                    model.setToolPolicy("fs.edit", auto = true)
                    model.setToolPolicy("shell", auto = true)
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("⚡ fs+shell AUTO", fontSize = 10.sp) }
            Button(
                onClick = { model.refreshTools() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("↻ ricarica", fontSize = 10.sp) }
        }
        if (model.tools.isEmpty()) {
            Text(
                "nessun tool — apri il pannello con il server raggiungibile",
                color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                items(model.tools) { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.name,
                                color = if (t.enabled) FTextMain else FTextMuted,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                            Text(
                                "approval=" + t.approval +
                                    (if (!t.enabled) " · disabilitato" else ""),
                                color = FTextMuted, fontSize = 9.sp
                            )
                        }
                        Text("AUTO", color = FTextMuted, fontSize = 9.sp)
                        Checkbox(
                            checked = t.approval == "auto",
                            onCheckedChange = { model.setToolPolicy(t.name, auto = it) }
                        )
                        Text("ON", color = FTextMuted, fontSize = 9.sp)
                        Checkbox(
                            checked = t.enabled,
                            onCheckedChange = { model.setToolPolicy(t.name, enabled = it) }
                        )
                    }
                }
            }
        }
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

/**
 * v1.6.17 (JAG-71) — MODEL picker (`GET /api/providers`). Lets the user drive any
 * configured backend from the phone: local llama.cpp (DGX / Windows), vLLM,
 * OpenRouter (GLM / DeepSeek flash …) or the original DeepSeek API. The first row
 * restores the server default so nothing is lost if the catalogue is unavailable.
 */
@Composable
private fun ForgeModelPicker(model: ForgeViewModel) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .background(FPanel, RoundedCornerShape(10.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MODELLO · /api/providers", color = FViolet, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
        }
        if (model.providers.isEmpty()) {
            Text(
                "nessun provider — premi ⟳ per ricaricare dal server",
                color = FTextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ModelRow("default del server (auto)", model.selectedModel == null) { model.selectModel(null) }
            model.providers.forEach { p ->
                Text(
                    (if (p.available) "● " else "○ ") + p.name + (if (p.local) " · locale" else ""),
                    color = if (p.available) FMint else FTextMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                p.models.forEach { m ->
                    val suffix = m.loaded?.let { if (it) " · in RAM" else " · non caricato" } ?: ""
                    ModelRow(m.id + suffix, model.selectedModel == m.ref) { model.selectModel(m.ref) }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeChip("⟳ ricarica") { model.refreshProviders() }
            if (model.selectedModel != null) ForgeChip("✕ default") { model.selectModel(null) }
        }
    }
}

/** One selectable row inside [ForgeModelPicker]; selected rows are mint + bold. */
@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(if (selected) FPanelRaised else FInk, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (selected) "◉ " else "○ ") + label,
            color = if (selected) FMint else FTextMain,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
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
                "TASK GRAPH · sess $runShort · $done/$total done",
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
                "Nessun piano ancora. Invia una richiesta: il modello scrive la lista " +
                    "(write_todos) e la aggiorna live ad ogni step.",
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
