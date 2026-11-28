package io.github.keplersj.selkie.link

import io.github.keplersj.selkie.engine.SelkieJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestNotificationTest {
    private fun obj(s: String) = SelkieJson.parseToJsonElement(s) as JsonObject

    @Test fun notifyLinesParse() {
        // As droidc host-link writes them (crates/droidc/tests/notifications.rs).
        val n = GuestNotification.parse(obj(
            """{"kind":"notify","key":"gtk:org.example.Chat-App/m1","app_id":"org.example.Chat-App","title":"Ada",
               "body":"Lunch?","icon":{"names":["avatar-default"]},"priority":"high","category":"im.received",
               "actions":[{"id":"0","label":"Reply","reply":true},{"id":"1","label":"Mark read"},{"label":"no id"}]}"""))!!
        assertEquals("gtk:org.example.Chat-App/m1", n.key)
        assertEquals("Ada", n.title)
        assertEquals("Lunch?", n.body)
        assertEquals("high", n.priority)
        assertEquals("Chat-App", n.source)
        assertEquals(listOf(GuestAction("0", "Reply", true), GuestAction("1", "Mark read")), n.actions)
        assertEquals(GuestNotification.CHANNEL_URGENT, GuestNotification.channelFor(n.priority))
        assertEquals("msg", GuestNotification.androidCategory(n.category))

        val bare = GuestNotification.parse(obj("""{"kind":"notify","key":"fdo:7","app_name":"Evolution","title":"New mail","priority":"normal"}"""))!!
        assertEquals("Evolution", bare.source)
        assertEquals("", bare.body)
        assertEquals(emptyList<GuestAction>(), bare.actions)
        assertEquals(GuestNotification.CHANNEL, GuestNotification.channelFor(bare.priority))
        assertNull(GuestNotification.androidCategory(null))

        assertNull(GuestNotification.parse(obj("""{"kind":"notify","title":"no key"}""")))
    }

    @Test fun hostLinesMatchWhatDroidcReads() {
        val reply = obj(GuestNotification.actionLine("gtk:a/b", "0", "Sure"))
        assertEquals("notification-action", reply["kind"]!!.jsonPrimitive.content)
        assertEquals("gtk:a/b", reply["key"]!!.jsonPrimitive.content)
        assertEquals("0", reply["action"]!!.jsonPrimitive.content)
        assertEquals("Sure", reply["text"]!!.jsonPrimitive.content)
        assertEquals(setOf("kind", "key"), obj(GuestNotification.actionLine("fdo:7", null, null)).keys)
        val closed = obj(GuestNotification.closedLine("fdo:7"))
        assertEquals("notification-closed", closed["kind"]!!.jsonPrimitive.content)
        assertEquals("fdo:7", closed["key"]!!.jsonPrimitive.content)
    }

    @Test fun scopedKeysKeepContainersApart() {
        val a = GuestNotification.scopedKey("bluefin", "gtk:a/b:c")
        val b = GuestNotification.scopedKey("aurora", "gtk:a/b:c")
        assertNotEquals(a, b)
        assertEquals("bluefin" to "gtk:a/b:c", GuestNotification.splitScopedKey(a))
        assertEquals("my:box" to "fdo:7", GuestNotification.splitScopedKey(GuestNotification.scopedKey("my:box", "fdo:7")))
        assertEquals("" to "fdo:7", GuestNotification.splitScopedKey(GuestNotification.scopedKey("", "fdo:7")))
        assertNull(GuestNotification.splitScopedKey("fdo:7"))
        assertNull(GuestNotification.splitScopedKey("9:short:x"))
        assertNull(GuestNotification.splitScopedKey(""))
    }
}
