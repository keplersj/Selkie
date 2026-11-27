package io.github.keplersj.selkie.platform.adb

/**
 * The container's own `adb`, as Selkie drives it to pair with and connect to this device's Wireless
 * debugging. Pure: the guest commands and how their output reads, so both are unit-tested.
 *
 * The guest's adb does the pairing itself, so its key stays in the guest's `~/.android` and Selkie never
 * holds one. Its server listens on a Unix socket in the container's `/run` ([SERVER_SOCKET_SPEC]), never on
 * TCP 5037: on Android every app shares localhost, and an adb server there would hand this device's shell
 * to any app that asked.
 */
object GuestAdb {
    /** Wireless debugging listens on all addresses; this device reaches it on loopback, as Shizuku does. */
    const val HOST = "127.0.0.1"

    /** The guest path of the server's socket; on the host it is under the container's run dir. */
    const val SERVER_SOCKET = "/run/selkie-adb.sock"
    /** What every guest session gets as `ADB_SERVER_SOCKET`, so its `adb` finds Selkie's server. */
    const val SERVER_SOCKET_SPEC = "localfilesystem:$SERVER_SOCKET"

    /** Exit status of [argv] when the container has no `adb`. */
    const val NO_ADB = 127

    /**
     * `adb` through a login shell, so it is found where the user put it (`~/.local/bin`, Homebrew, the
     * image), with `~/.local/bin` and `~/bin` first in case the image's profile leaves them out.
     */
    private const val SCRIPT =
        "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$PATH\"; " +
            "command -v adb >/dev/null 2>&1 || { echo 'no adb in this container' >&2; exit $NO_ADB; }; " +
            "exec adb \"\$@\""

    fun argv(vararg args: String): List<String> = listOf("/bin/bash", "-lc", SCRIPT, "adb") + args

    /** The server in the foreground, so it lives exactly as long as the proot Selkie keeps for it. */
    fun server(): List<String> = argv("nodaemon", "server")
    fun killServer(): List<String> = argv("kill-server")
    /** The code goes on stdin, not in argv, where any process of the app's uid could read it. */
    fun pair(port: Int): List<String> = argv("pair", "$HOST:$port")
    fun connect(port: Int): List<String> = argv("connect", "$HOST:$port")
    fun disconnect(port: Int): List<String> = argv("disconnect", "$HOST:$port")

    /** Only the digits of what was typed: the Settings dialog shows the code as six digits. */
    fun cleanCode(typed: CharSequence?): String = typed?.filter { it in '0'..'9' }?.toString().orEmpty()

    sealed interface PairResult {
        data object Paired : PairResult
        data object WrongCode : PairResult
        data object NoAdb : PairResult
        data class Failed(val message: String) : PairResult
    }

    /** `adb pair` exits 0 whether or not it paired, so the result is in what it printed. */
    fun pairResult(exit: Int, output: String): PairResult = when {
        exit == NO_ADB && "no adb in this container" in output -> PairResult.NoAdb
        "Successfully paired" in output -> PairResult.Paired
        "Wrong password" in output -> PairResult.WrongCode
        else -> PairResult.Failed(lastLine(output) ?: "adb pair exited with status $exit")
    }

    sealed interface ConnectResult {
        data object Connected : ConnectResult
        data object NoAdb : ConnectResult
        data class Failed(val message: String) : ConnectResult
    }

    /** Like `adb pair`, `adb connect` exits 0 when it fails. */
    fun connectResult(exit: Int, output: String): ConnectResult = when {
        exit == NO_ADB && "no adb in this container" in output -> ConnectResult.NoAdb
        output.lineSequence().any { it.startsWith("connected to") || it.startsWith("already connected to") } ->
            ConnectResult.Connected
        else -> ConnectResult.Failed(lastLine(output) ?: "adb connect exited with status $exit")
    }

    /** The prompt and the reply share a line when the code comes from stdin, so the prompt is cut off. */
    private fun lastLine(output: String): String? =
        output.lineSequence().map { it.trim().removePrefix("Enter pairing code:").trim() }
            .lastOrNull { it.isNotEmpty() }
}
