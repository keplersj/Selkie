package io.github.keplersj.selkie.platform

import android.content.Intent
import org.robolectric.RuntimeEnvironment
import io.github.keplersj.selkie.AppActivity
import io.github.keplersj.selkie.engine.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShortcutsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val editor = AppEntry(id = "org.gnome.TextEditor.desktop", name = "Text Editor")

    @Test fun aShortcutOpensItsAppInItsContainer() {
        val intent = AppShortcuts.intent(ctx, "fedora", editor)
        assertEquals(AppActivity::class.java.name, intent.component?.className)
        assertEquals(AppShortcuts.Target("fedora", "org.gnome.TextEditor.desktop", "Text Editor"), AppShortcuts.target(intent))
    }

    @Test fun eachAppAndContainerIsItsOwnDocument() {
        // documentLaunchMode="intoExisting" matches tasks by data, so each app gets its own task.
        val a = AppShortcuts.intent(ctx, "fedora", editor).data
        val b = AppShortcuts.intent(ctx, "bluefin", editor).data
        val c = AppShortcuts.intent(ctx, "fedora", editor.copy(id = "org.gnome.Nautilus.desktop")).data
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a, AppShortcuts.intent(ctx, "fedora", editor).data)
    }

    @Test fun otherIntentsAreNotShortcuts() {
        assertNull(AppShortcuts.target(null))
        assertNull(AppShortcuts.target(Intent(Intent.ACTION_MAIN)))
        assertNull(AppShortcuts.target(Intent(AppShortcuts.ACTION).putExtra(AppShortcuts.EXTRA_CONTAINER, "fedora")))
    }

    @Test fun idsNameTheContainerAndTheApp() {
        assertEquals("app:fedora/org.gnome.TextEditor.desktop", AppShortcuts.id("fedora", "org.gnome.TextEditor.desktop"))
    }
}
