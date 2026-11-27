package io.github.keplersj.selkie.ui

import io.github.keplersj.selkie.engine.AppEntry
import io.github.keplersj.selkie.engine.AppOrigin
import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.HiddenApp
import io.github.keplersj.selkie.engine.SelkieJson
import io.github.keplersj.selkie.engine.apps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppsModelTest {
    private fun app(name: String, vararg categories: String, origin: AppOrigin = AppOrigin.IMAGE) =
        AppEntry(id = "$name.desktop", name = name, categories = categories.toList(), argv = listOf(name.lowercase()),
            origin = origin, path = "/usr/share/applications/$name.desktop")

    @Test fun readsDroidcsAppList() {
        val json = """{"container":"default","apps":[{"id":"org.gnome.TextEditor.desktop","name":"Text Editor",
            "generic_name":null,"comment":"Edit text files","categories":["Utility"],"keywords":[],"mime_types":["text/plain"],
            "exec":"gnome-text-editor %U","argv":["gnome-text-editor"],"terminal":false,"only_show_in":[],
            "needs_desktop":false,"dbus_activatable":true,"startup_wm_class":null,"origin":"user-flatpak",
            "path":"/usr/share/applications/org.gnome.TextEditor.desktop",
            "icon":{"name":"org.gnome.TextEditor","host_path":"/data/x.svg","format":"svg"},
            "actions":[{"id":"new-window","name":"New Window","argv":["gnome-text-editor","--new-window"]}]}],
            "hidden":[{"id":"mimeinfo.desktop","origin":"image","reason":"no-display"}]}"""
        val l = DroidcOutcome.Success(SelkieJson.parseToJsonElement(json)).apps()
        val a = l.apps.single()
        assertEquals(AppOrigin.USER_FLATPAK, a.origin)
        assertEquals("/data/x.svg", a.icon?.hostPath)
        assertEquals(listOf("text/plain"), a.mimeTypes)
        assertEquals("New Window", a.actions.single().name)
        assertEquals("no-display", l.hidden.single().reason)
    }

    @Test fun filtersAreTheMainCategoriesMostUsedFirst() {
        val apps = listOf(app("A", "GNOME", "Utility"), app("B", "Utility"), app("C", "Development"),
            app("D", "AudioVideo", "Player"), app("E", "Network"), app("F", "Game"), app("G", "X-Custom"))
        assertEquals(listOf("Utilities", "Development", "Games", "Internet"), categoryFilters(apps))
        assertNull(mainCategory(apps.last()))
    }

    @Test fun openingDependsOnTheEntry() {
        assertEquals(AppOpen.Alone, appOpen(app("Files")))
        assertEquals(AppOpen.Desktop, appOpen(app("Settings").copy(onlyShowIn = listOf("GNOME"), needsDesktop = true)))
        assertEquals(AppOpen.None, appOpen(app("Htop").copy(terminal = true)))
        assertEquals(AppOpen.None, appOpen(app("Broken").copy(argv = emptyList())))
    }

    @Test fun groupsAndSupportingLines() {
        assertEquals(AppGroup.Flatpak, appGroup(app("Calc", origin = AppOrigin.USER_FLATPAK)))
        val zed = app("Zed", origin = AppOrigin.USER).copy(path = "/var/home/kepler/.local/share/applications/dev.zed.Zed.desktop")
        assertEquals(AppGroup.User, appGroup(zed))
        assertEquals("From ~/.local/share/applications", appSupporting(zed))
        assertEquals("Utilities", appSupporting(app("Files", "Utility")))
        assertEquals("Needs the full desktop", appSupporting(app("Settings").copy(needsDesktop = true)))
    }

    @Test fun hiddenNoteSaysWhy() {
        assertNull(hiddenNote(emptyList()))
        assertEquals("1 entry is hidden: marked not for display.",
            hiddenNote(listOf(HiddenApp("a", reason = "no-display"))))
        assertEquals("12 entries are hidden: marked not for display, or program not installed.",
            hiddenNote(List(9) { HiddenApp("a$it", reason = "hidden") } + List(3) { HiddenApp("b$it", reason = "not-installed") }))
    }
}
