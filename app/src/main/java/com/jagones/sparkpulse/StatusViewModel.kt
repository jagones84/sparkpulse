package com.jagones.sparkpulse

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionState { OK, RETRY, OFFLINE }

data class DashboardState(
    val host: String = DEFAULT_HOST,
    val snapshot: StatusSnapshot = StatusSnapshot(),
    val connection: ConnectionState = ConnectionState.RETRY,
    val demoMode: Boolean = false,
    val updatedAtMillis: Long? = null,
    val consecutiveFailures: Int = 0,
    val pollIntervalMillis: Long = 2_000L,
    val history: List<TelemetryPoint> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val modelCommandMessage: String? = null,
    val modelCommandInProgress: Boolean = false
)

/**
 * Default DGX Spark host: its Tailscale address on the private tailnet, so a
 * phone on the tailnet reaches both the dashboard (`:8787`) and SparkForge
 * (`:8790`) without port-forwarding. Never `127.0.0.1` (that would only work on
 * the DGX itself). Overridable at runtime from the FORGE settings panel.
 */
const val DEFAULT_HOST = "100.102.61.23"

// Number of consecutive failed polls before the UI marks the link OFFLINE.
// A single transient failure only shows RETRY and keeps the last good data.
private const val OFFLINE_AFTER_FAILURES = 2

class StatusViewModel(
    private val repository: StatusRepository = StatusRepository()
) : ViewModel() {
    var state = mutableStateOf(DashboardState())
        private set
    private var pollingJob: Job? = null
    private var modelPollingJob: Job? = null

    init {
        startPolling()
        refreshModels()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val pollStartedAt = System.currentTimeMillis()
                refresh()
                val remainingInterval = state.value.pollIntervalMillis - (System.currentTimeMillis() - pollStartedAt)
                if (remainingInterval > 0) delay(remainingInterval)
            }
        }
    }

    fun setHost(host: String) {
        state.value = state.value.copy(host = host)
        refreshModels()
        refreshNow()
    }

    fun setPollIntervalMillis(intervalMillis: Int) {
        val interval = normalizePollIntervalMillis(intervalMillis)
        if (state.value.pollIntervalMillis == interval) return
        state.value = state.value.copy(pollIntervalMillis = interval)
        startPolling()
    }

    fun refreshModels() {
        if (state.value.demoMode) return
        viewModelScope.launch {
            val host = state.value.host
            try {
                val models = withContext(Dispatchers.IO) { repository.fetchModels(host) }
                if (state.value.host == host) state.value = state.value.copy(models = models, modelCommandMessage = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(modelCommandMessage = "Errore caricamento modelli: ${e.message ?: "rete non disponibile"}")
            }
        }
    }

    fun switchModel(alias: String) {
        if (alias.isBlank() || state.value.modelCommandInProgress || state.value.demoMode) return
        modelPollingJob = viewModelScope.launch {
            val host = state.value.host
            state.value = state.value.copy(modelCommandInProgress = true, modelCommandMessage = "Avvio switch a $alias…")
            try {
                withContext(Dispatchers.IO) { repository.switchModel(host, alias) }
                while (isActive) {
                    delay(2_000)
                    val models = withContext(Dispatchers.IO) { repository.fetchModels(host) }
                    state.value = state.value.copy(models = models)
                    if (models.any { it.alias == alias && it.loaded }) {
                        state.value = state.value.copy(modelCommandMessage = "Modello $alias caricato")
                        refreshNow()
                        break
                    }
                    state.value = state.value.copy(modelCommandMessage = "Caricamento di $alias in corso…")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(modelCommandMessage = "Switch non riuscito: ${e.message ?: "errore di rete"}")
            } finally {
                state.value = state.value.copy(modelCommandInProgress = false)
            }
        }
    }

    fun dismissModelMessage() {
        state.value = state.value.copy(modelCommandMessage = null)
    }

    fun toggleDemoMode() {
        state.value = state.value.copy(
            demoMode = !state.value.demoMode,
            snapshot = if (!state.value.demoMode) DemoStatus.snapshot else state.value.snapshot
        )
        if (state.value.demoMode) dismissModelMessage() else refreshModels()
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val current = state.value
        if (current.demoMode) return
        try {
            val snapshot = withContext(Dispatchers.IO) { repository.fetch(current.host) }
            if (state.value.host != current.host || state.value.demoMode) return
            state.value = state.value.copy(
                snapshot = snapshot,
                connection = ConnectionState.OK,
                updatedAtMillis = System.currentTimeMillis(),
                consecutiveFailures = 0
            )
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.fetchHistory(current.host) } }
                    .onSuccess { history ->
                        if (state.value.host == current.host && !state.value.demoMode) {
                            state.value = state.value.copy(history = history)
                        }
                    }
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            if (state.value.host != current.host) return
            // Keep the last known good snapshot: never swap real data for
            // demo/empty values on a failed poll. Only the connection flag
            // changes, and only after enough consecutive failures.
            val failures = current.consecutiveFailures + 1
            state.value = state.value.copy(
                connection = if (failures >= OFFLINE_AFTER_FAILURES) ConnectionState.OFFLINE else ConnectionState.RETRY,
                consecutiveFailures = failures
            )
        }
    }
}

private object DemoStatus {
    val snapshot = StatusSnapshot(
        host = "spark-6263 · demo",
        gpu = GpuStatus(utilization = 47.0, temperatureC = 52.0, powerW = 68.0, totalPowerW = 120.0),
        memory = MemoryStatus(unifiedUsedGb = 74.2, unifiedTotalGb = 128.0, ramUsedGb = 69.1, ramTotalGb = 128.0, vramUsedGb = 42.8),
        model = ModelStatus(loaded = true, name = "qwen-local-32b", vramGb = 42.8, pid = 62124),
        topProcesses = listOf(
            GpuProcess(62124, "llama-server", 42.8),
            GpuProcess(4821, "comfyui", 8.4),
            GpuProcess(790, "python", 3.2)
        ),
        uptimeSeconds = 345_600
    )
}
