package io.github.keplersj.selkie.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Links the desktop opened (`xdg-open`, a GNOME app, the OpenURI portal), sent by `droidc host-link` as
 * `open-uri` lines and opened with Android's own apps: the browser for web links, the mail, phone, SMS or
 * maps app for the others. droidc only sends these schemes; Selkie checks again, since a link is the
 * guest choosing which Android app to start.
 */
object OpenUri {
    val SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")

    /** The link in an `open-uri` line, if Android should open it. */
    fun uriFrom(m: GuestLinkMessage): String? {
        if (m.kind != "open-uri") return null
        val uri = runCatching { m.body["uri"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        return uri.takeIf { allowed(it) }
    }

    fun allowed(uri: String): Boolean {
        val colon = uri.indexOf(':')
        if (colon <= 0 || colon == uri.length - 1 || uri.length > 8192) return false
        if (uri.any { it.isISOControl() }) return false
        return uri.substring(0, colon).lowercase() in SCHEMES
    }

    /** Opens [uri] in a new task. False when no app takes it. */
    fun open(context: Context, uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("SelkieLink", "no app opens ${uri.substringBefore(':')} links")
            false
        } catch (e: SecurityException) {
            Log.w("SelkieLink", "could not open a ${uri.substringBefore(':')} link: ${e.message}")
            false
        }
    }
}
