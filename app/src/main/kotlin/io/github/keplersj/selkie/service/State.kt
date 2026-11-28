package io.github.keplersj.selkie.service

import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.AppList
import io.github.keplersj.selkie.engine.ContainerInfo
import io.github.keplersj.selkie.engine.DroidcError
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.GraphicalSession
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.SessionPick
import io.github.keplersj.selkie.engine.StatusData

enum class OpKind(val label: String) {
    SWITCH("Installing"), UPGRADE("Upgrading"), ROLLBACK("Rolling back"),
    FIX_OWNERSHIP("Applying image ownership"), UNDO_OWNERSHIP("Giving files back to Selkie"),
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
        /** Container the operation acts on. */
        val container: String = EngineEnv.DEFAULT_CONTAINER,
        /** Installing without a signature check. */
        val insecure: Boolean = false,
        /** The container had no image before, so this install creates it. */
        val creating: Boolean = false,
    ) : Operation
    data class Failed(
        val kind: OpKind,
        val ref: String?,
        val insecure: Boolean,
        val error: DroidcError,
        val stderrTail: String,
        /** Container the operation acted on, so a retry goes to the same one. */
        val container: String = EngineEnv.DEFAULT_CONTAINER,
        val creating: Boolean = false,
    ) : Operation
    data class Done(
        val kind: OpKind,
        val summary: String,
        val container: String = EngineEnv.DEFAULT_CONTAINER,
        val creating: Boolean = false,
    ) : Operation
    data class Cancelled(val kind: OpKind) : Operation
}

data class SessionInfo(
    val id: String,
    val title: String,
    val running: Boolean,
    val container: String = EngineEnv.DEFAULT_CONTAINER,
)

/** The graphical session: Selkie's compositor plus `droidc supervise --session` under proot. */
sealed interface DesktopState {
    data object Stopped : DesktopState
    data object Starting : DesktopState
    data class Running(val width: Int, val height: Int) : DesktopState
    data class Failed(val message: String, val logTail: String) : DesktopState
}

data class ServiceState(
    /** The container Home shows and new operations, terminals and the desktop act on. */
    val container: String = EngineEnv.DEFAULT_CONTAINER,
    /**
     * Containers under the droidc root, `default` always first. The default one is listed even before
     * anything is installed in it.
     */
    val containers: List<ContainerInfo> = listOf(ContainerInfo(EngineEnv.DEFAULT_CONTAINER)),
    val operation: Operation = Operation.Idle,
    val status: StatusData? = null,
    /** null = not loaded yet. */
    val statusError: String? = null,
    val statusLoaded: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    /** The current deployment's graphical session (from `shell-spec`); null until known. */
    val graphical: GraphicalSession? = null,
    /** Every session the current deployment offers (from `shell-spec`); empty with older droidc. */
    val graphicalSessions: List<SessionEntry> = emptyList(),
    /** How the selected container picks its session on this device. */
    val sessionPick: SessionPick = SessionPick(),
    /** Name of the session the desktop runs, while it runs. */
    val desktopSession: String? = null,
    val desktop: DesktopState = DesktopState.Stopped,
    /** Container the desktop belongs to while it is starting or running. One desktop at a time. */
    val desktopContainer: String? = null,
    /** The app the compositor shows on its own, instead of the desktop; null for the desktop. */
    val desktopApp: AppEntry? = null,
    /** The selected container's apps (`droidc apps`); null until read. */
    val apps: AppList? = null,
    /** Why the apps couldn't be read, such as a droidc without `apps`. */
    val appsError: String? = null,
    /** Root mode is on for the selected container (droidc's mutating commands and terminals run through su). */
    val rootMode: Boolean = false,
    /** An su binary exists on this device. */
    val suFound: Boolean = false,
    /** Waiting for the root manager's grant prompt. */
    val rootProbing: Boolean = false,
    /** Last root-mode message (grant result, errors). */
    val rootMessage: String? = null,
    /** Containers whose sessions Android closed, until dismissed. */
    val closed: List<Closed> = emptyList(),
)

/** `default` first, then the rest by name; [selected] is kept even before droidc lists it. */
fun mergeContainers(listed: List<ContainerInfo>, selected: String): List<ContainerInfo> {
    val byName = listed.associateBy { it.name }.toMutableMap()
    byName.getOrPut(EngineEnv.DEFAULT_CONTAINER) { ContainerInfo(EngineEnv.DEFAULT_CONTAINER) }
    byName.getOrPut(selected) { ContainerInfo(selected) }
    return byName.values.sortedWith(compareBy({ it.name != EngineEnv.DEFAULT_CONTAINER }, { it.name }))
}
