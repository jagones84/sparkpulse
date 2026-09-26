package com.jagones.sparkpulse

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
    val updatedAtMillis: Long? = null
)

const val DEFAULT_HOST = "100.102.61.23"

class StatusViewModel(
    private val repository: StatusRepository = StatusRepository()
) : ViewModel() {
    var state = mutableStateOf(DashboardState())
        private set

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000)
            }
        }
    }

    fun setHost(host: String) {
        state.value = state.value.copy(host = host)
        refreshNow()
    }

    fun toggleDemoMode() {
        state.value = state.value.copy(
            demoMode = !state.value.demoMode,
            snapshot = if (!state.value.demoMode) DemoStatus.snapshot else state.value.snapshot
        )
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val current = state.value
        if (current.demoMode) return
        state.value = current.copy(connection = ConnectionState.RETRY)
        runCatching { withContext(Dispatchers.IO) { repository.fetch(current.host) } }
            .onSuccess { snapshot ->
                state.value = state.value.copy(
                    snapshot = snapshot,
                    connection = ConnectionState.OK,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            .onFailure {
                state.value = state.value.copy(
                    connection = ConnectionState.OFFLINE,
                    snapshot = DemoStatus.snapshot,
                    updatedAtMillis = null
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
