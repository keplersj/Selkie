package io.github.keplersj.selkie.ui

import io.github.keplersj.selkie.engine.AppAction
import io.github.keplersj.selkie.engine.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAppsTest {
    private fun app(name: String, vararg categories: String) =
        AppEntry(id = "$name.desktop", name = name, categories = categories.toList(), argv = listOf(name.lowercase()),
            path = "/usr/share/applications/$name.desktop")

    private val steam = app("Steam", "Network", "Game").copy(actions = listOf(
        AppAction("Store", "Store", listOf("steam", "steam://store")),
        AppAction("BigPicture", "Big Picture", listOf("steam", "steam://open/bigpicture"))))

    @Test fun bigPictureIsWhatATvOpens() {
        val e = tvEntry(steam)
        assertEquals("Steam Big Picture", e.name)
        assertEquals(listOf("steam", "steam://open/bigpicture"), e.argv)
        assertEquals(app("Files"), tvEntry(app("Files")))
    }

    @Test fun bigScreenAppsLeadAndNeedNoPointer() {
        val files = app("Files", "Utility")
        val kodi = app("Kodi", "AudioVideo", "Player")
        val htop = app("Htop", "System").copy(terminal = true)
        val row = tvApps(listOf(files, htop, kodi, steam))
        assertEquals(listOf("Kodi", "Steam Big Picture", "Files"), row.map { it.name })
        assertTrue(bigScreen(steam))
        assertTrue(bigScreen(kodi))
        assertFalse(bigScreen(files))
        assertEquals("dev · ready", tvAppLine("dev", kodi, TvInputs()))
        assertEquals("dev · needs a pointer", tvAppLine("dev", files, TvInputs()))
        assertEquals("dev · ready", tvAppLine("dev", files, TvInputs(pointer = true)))
    }
}
