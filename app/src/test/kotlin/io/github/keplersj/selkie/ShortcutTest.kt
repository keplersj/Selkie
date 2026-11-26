package io.github.keplersj.selkie

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.github.keplersj.selkie.ui.Section
import org.junit.Test

class ShortcutTest {
    @Test fun ctrlKeysMatchTheDesktopWindowDesign() {
        assertEquals(Shortcut.NewTerminal, shortcutFor(KeyEvent.KEYCODE_T))
        assertEquals(Shortcut.NewContainer, shortcutFor(KeyEvent.KEYCODE_N))
        assertNull(shortcutFor(KeyEvent.KEYCODE_Q))
    }

    @Test fun ctrlNumbersOpenTheSectionsInOrder() {
        assertEquals(Section.Containers, sectionFor(KeyEvent.KEYCODE_1))
        assertEquals(Section.Images, sectionFor(KeyEvent.KEYCODE_2))
        assertEquals(Section.Activity, sectionFor(KeyEvent.KEYCODE_3))
        assertEquals(Section.Settings, sectionFor(KeyEvent.KEYCODE_4))
        assertNull(sectionFor(KeyEvent.KEYCODE_5))
    }
}
