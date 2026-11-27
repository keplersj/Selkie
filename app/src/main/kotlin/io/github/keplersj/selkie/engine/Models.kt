package io.github.keplersj.selkie.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared lenient JSON config: droidc may add fields; we must not break on them. */
val SelkieJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** Error kinds from the droidc contract (design-mvp.md, "--json output"). */
object ErrorKind {
    const val NETWORK = "network"
    const val SIGNATURE = "signature"
    const val STORAGE = "storage"
    const val NOT_FOUND = "not-found"
    const val INVALID = "invalid"
    const val LOCKED = "locked"
    const val INTERNAL = "internal"
}

@Serializable
data class DroidcError(val kind: String = ErrorKind.INTERNAL, val message: String = "")

/** One NDJSON line from `droidc --json`. */
sealed interface DroidcEvent {
    data class Progress(
        val phase: String,
        val current: Long,
        val total: Long?,
        val unit: String?,
        val message: String?,
    ) : DroidcEvent

    data class Log(val level: String, val message: String) : DroidcEvent

    /** Final line. [data] is set when ok, [error] when not. */
    data class Result(val ok: Boolean, val data: JsonElement?, val error: DroidcError?) : DroidcEvent

    /** A line that was valid JSON but had an unknown `event`. */
    data class Unknown(val raw: String) : DroidcEvent
}

object NdjsonParser {
    /** Returns null for blank lines. Non-JSON lines become warn-level [DroidcEvent.Log]s. */
    fun parseLine(line: String): DroidcEvent? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val obj = try {
            SelkieJson.parseToJsonElement(t) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return DroidcEvent.Log("warn", "non-JSON output: $t")
        return when (obj["event"]?.jsonPrimitive?.contentOrNull) {
            "progress" -> DroidcEvent.Progress(
                phase = obj.str("phase") ?: "",
                current = obj.long("current") ?: 0L,
                total = obj.long("total"),
                unit = obj.str("unit"),
                message = obj.str("message"),
            )
            "log" -> DroidcEvent.Log(obj.str("level") ?: "info", obj.str("message") ?: "")
            "result" -> {
                val ok = (obj["ok"] as? JsonPrimitive)?.contentOrNull == "true"
                val err = obj["error"]?.takeIf { it is JsonObject }?.let {
                    SelkieJson.decodeFromJsonElement<DroidcError>(it)
                }
                DroidcEvent.Result(ok, obj["data"], if (ok) null else (err ?: DroidcError(message = "unknown error")))
            }
            else -> DroidcEvent.Unknown(t)
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}

// ---- status / deployment record ----

@Serializable
data class Signature(
    val verified: Boolean = false,
    val mode: String? = null,
    val subject: String? = null,
    val issuer: String? = null,
    @SerialName("policy_match") val policyMatch: String? = null,
)

@Serializable
data class DeployStats(
    @SerialName("download_bytes") val downloadBytes: Long = 0,
    val files: Long = 0,
    @SerialName("bytes_on_disk") val bytesOnDisk: Long = 0,
    val hardlinks: Long = 0,
    @SerialName("link_strategy") val linkStrategy: String? = null,
    @SerialName("unpack_seconds") val unpackSeconds: Double = 0.0,
)

@Serializable
data class Deployment(
    val id: String = "",
    @SerialName("image_ref") val imageRef: String = "",
    @SerialName("manifest_digest") val manifestDigest: String = "",
    @SerialName("index_digest") val indexDigest: String? = null,
    @SerialName("config_digest") val configDigest: String? = null,
    val platform: String? = null,
    val created: String? = null,
    @SerialName("deployed_at") val deployedAt: String? = null,
    @SerialName("session_label") val sessionLabel: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val signature: Signature? = null,
    val insecure: Boolean = false,
    val stats: DeployStats? = null,
    /** `"image"` once the image's owners/setuid were applied (root mode); null otherwise. */
    val ownership: String? = null,
) {
    val imageOwned: Boolean get() = ownership == "image"

    /** `sha256:abcdef...` -> `abcdef123456` */
    val shortDigest: String get() = manifestDigest.substringAfter(':').take(12)
}

@Serializable
data class StatusData(
    val root: String? = null,
    val platform: JsonElement? = null,
    val current: Deployment? = null,
    val previous: Deployment? = null,
)

/** A deployment as `droidc container list` names it. */
@Serializable
data class ContainerDeployment(
    val id: String = "",
    @SerialName("image_ref") val imageRef: String? = null,
)

/** One container under the droidc root (`droidc container list`). */
@Serializable
data class ContainerInfo(
    val name: String,
    val current: ContainerDeployment? = null,
    val previous: ContainerDeployment? = null,
)

@Serializable
data class ContainerList(val containers: List<ContainerInfo> = emptyList())

@Serializable
data class Bind(val host: String, val guest: String)

/**
 * How the deployment's graphical session starts (droidc `shell-spec` `session`, also `droidc session
 * show`): from the image's `containers.droidc.session.*` labels or the user's override.
 */
@Serializable
data class GraphicalSession(
    val name: String? = null,
    /** `wayland` (nested in Selkie's compositor) or `screencast` (headless, shown by droidc bridge). */
    val renderer: String? = null,
    val exec: List<String>? = null,
    val services: List<List<String>> = emptyList(),
    val configured: Boolean = false,
    /** `label` or `override`. */
    val source: String? = null,
)

@Serializable
data class ShellSpec(
    val rootfs: String,
    val binds: List<Bind> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = "/root",
    @SerialName("session_label") val sessionLabel: String? = null,
    val session: GraphicalSession? = null,
    /** Guest paths to bind the guest droidc over: the sudo/su/pkexec shims (proot only). */
    val shims: List<String> = emptyList(),
)

/** Where a desktop entry came from (droidc `apps`). */
@Serializable
enum class AppOrigin {
    @SerialName("image") IMAGE,
    @SerialName("flatpak") FLATPAK,
    @SerialName("user") USER,
    @SerialName("user-flatpak") USER_FLATPAK,
}

@Serializable
data class AppIcon(
    val name: String,
    /** The icon file, readable by Selkie; null when the theme has none. */
    @SerialName("host_path") val hostPath: String? = null,
    /** `png`, `svg` or `xpm`. */
    val format: String? = null,
)

@Serializable
data class AppAction(val id: String, val name: String, val argv: List<String>)

/** One application of a container (droidc `apps`), read from its desktop entry. */
@Serializable
data class AppEntry(
    /** Desktop ID, such as `org.gnome.TextEditor.desktop`. */
    val id: String,
    val name: String,
    @SerialName("generic_name") val genericName: String? = null,
    val comment: String? = null,
    val categories: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    @SerialName("mime_types") val mimeTypes: List<String> = emptyList(),
    val exec: String = "",
    /** `Exec` split into arguments, without the field codes for files and URLs. */
    val argv: List<String> = emptyList(),
    val terminal: Boolean = false,
    @SerialName("only_show_in") val onlyShowIn: List<String> = emptyList(),
    /** The entry names desktops it belongs to: Selkie opens it inside the full desktop. */
    @SerialName("needs_desktop") val needsDesktop: Boolean = false,
    val origin: AppOrigin = AppOrigin.IMAGE,
    /** Guest path of the entry. */
    val path: String = "",
    val icon: AppIcon? = null,
    val actions: List<AppAction> = emptyList(),
)

@Serializable
data class HiddenApp(val id: String, val origin: AppOrigin = AppOrigin.IMAGE, val reason: String = "")

@Serializable
data class AppList(
    val container: String = "",
    val apps: List<AppEntry> = emptyList(),
    val hidden: List<HiddenApp> = emptyList(),
)

/** Final outcome of a droidc invocation. */
sealed interface DroidcOutcome {
    data class Success(val data: JsonElement?) : DroidcOutcome
    data class Failure(val error: DroidcError, val stderrTail: String = "") : DroidcOutcome
}

fun DroidcOutcome.Success.status(): StatusData =
    SelkieJson.decodeFromJsonElement(data ?: JsonObject(emptyMap()))
fun DroidcOutcome.Success.graphicalSession(): GraphicalSession =
    SelkieJson.decodeFromJsonElement(data ?: error("session returned no data"))
fun DroidcOutcome.Success.containers(): List<ContainerInfo> =
    SelkieJson.decodeFromJsonElement<ContainerList>(data ?: JsonObject(emptyMap())).containers
fun DroidcOutcome.Success.shellSpec(): ShellSpec =
    SelkieJson.decodeFromJsonElement(data ?: error("shell-spec returned no data"))
fun DroidcOutcome.Success.apps(): AppList =
    SelkieJson.decodeFromJsonElement(data ?: error("apps returned no data"))
