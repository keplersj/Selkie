package io.github.keplersj.selkie.service

import io.github.keplersj.selkie.engine.DroidcError
import io.github.keplersj.selkie.engine.StatusData

enum class OpKind(val label: String) {
    SWITCH("Installing"), UPGRADE("Upgrading"), ROLLBACK("Rolling back")
}

sealed interface Operation {
    data object Idle : Operation
    data class Running(
        val kind: OpKind,
        val ref: String?,
        val phase: String = "starting",
        val current: Long = 0,
        val total: Long? = null,
        val unit: String? = null,
        val message: String? = null,
    ) : Operation
    data class Failed(
        val kind: OpKind,
        val ref: String?,
        val insecure: Boolean,
        val error: DroidcError,
        val stderrTail: String,
    ) : Operation
    data class Done(val kind: OpKind, val summary: String) : Operation
    data class Cancelled(val kind: OpKind) : Operation
}

data class SessionInfo(val id: String, val title: String, val running: Boolean)

data class ServiceState(
    val operation: Operation = Operation.Idle,
    val status: StatusData? = null,
    /** null = not loaded yet. */
    val statusError: String? = null,
    val statusLoaded: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
)
