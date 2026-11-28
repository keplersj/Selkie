package io.github.keplersj.selkie.usb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The guest's usbshim asks for a device over [UsbBridge]'s socket, one request line and one reply
 * line per connection (droidc's docs/design/usb.md):
 *
 * ```
 * → {"id":1,"method":"usb.open","params":{"bus":1,"dev":5}}
 * ← {"id":1,"result":{}}                                    + the usbfs fd
 * ← {"id":1,"error":{"kind":"not-allowed","message":"…"}}   no fd
 * ```
 */
object UsbProtocol {
    data class Open(val id: Long, val bus: Int, val dev: Int)

    /** Error kinds the shim turns into errno values. */
    const val NOT_ALLOWED = "not-allowed" // EACCES
    const val GONE = "gone" // ENODEV
    const val BAD_REQUEST = "bad-request" // EINVAL

    /** The request, or why it isn't one (with the id to answer, when it had one). */
    sealed interface Parsed {
        data class Ok(val open: Open) : Parsed
        data class Bad(val id: Long, val message: String) : Parsed
    }

    fun parse(line: String): Parsed {
        val o: JsonObject = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) {
            return Parsed.Bad(0, "not a JSON object")
        }
        val id = (try { o["id"]?.jsonPrimitive?.longOrNull } catch (_: Exception) { null }) ?: 0
        val method = try { o["method"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        if (method != "usb.open") return Parsed.Bad(id, "unknown method $method")
        val params = try { o["params"]?.jsonObject } catch (_: Exception) { null }
        val bus = params?.get("bus")?.let { try { it.jsonPrimitive.intOrNull } catch (_: Exception) { null } }
        val dev = params?.get("dev")?.let { try { it.jsonPrimitive.intOrNull } catch (_: Exception) { null } }
        if (bus == null || dev == null || bus !in 1..999 || dev !in 1..999) return Parsed.Bad(id, "bus and dev must be 1 to 999")
        return Parsed.Ok(Open(id, bus, dev))
    }

    fun ok(id: Long): String = buildJsonObject { put("id", id); putJsonObject("result") {} }.toString() + "\n"

    fun error(id: Long, kind: String, message: String): String = buildJsonObject {
        put("id", id)
        putJsonObject("error") { put("kind", kind); put("message", message) }
    }.toString() + "\n"
}
