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

@Serializable
data class Bind(val host: String, val guest: String)

@Serializable
data class ShellSpec(
    val rootfs: String,
    val binds: List<Bind> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = "/root",
    @SerialName("session_label") val sessionLabel: String? = null,
)

/** Final outcome of a droidc invocation. */
sealed interface DroidcOutcome {
    data class Success(val data: JsonElement?) : DroidcOutcome
    data class Failure(val error: DroidcError, val stderrTail: String = "") : DroidcOutcome
}

fun DroidcOutcome.Success.status(): StatusData =
    SelkieJson.decodeFromJsonElement(data ?: JsonObject(emptyMap()))
fun DroidcOutcome.Success.shellSpec(): ShellSpec =
    SelkieJson.decodeFromJsonElement(data ?: error("shell-spec returned no data"))
