package io.github.keplersj.selkie

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutTest {
    @Test fun ctrlKeysMatchTheDesktopWindowDesign() {
        assertEquals(Shortcut.NewTerminal, shortcutFor(KeyEvent.KEYCODE_T))
        assertEquals(Shortcut.NewContainer, shortcutFor(KeyEvent.KEYCODE_N))
        assertNull(shortcutFor(KeyEvent.KEYCODE_Q))
    }
}
