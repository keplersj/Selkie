package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.DroidcOutcome
import io.github.keplersj.selkie.engine.DroidcEvent
import io.github.keplersj.selkie.engine.NdjsonParser
import io.github.keplersj.selkie.engine.SessionChoice
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.SessionPick
import io.github.keplersj.selkie.engine.shellSpec
import io.github.keplersj.selkie.ui.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChoiceTest {
    private val gnome = SessionEntry("label", "label", "GNOME", formFactors = listOf("tablet", "desktop"), runnable = true)
    private val phosh = SessionEntry("sm.puri.Phosh", "xdg", "Phosh", formFactors = listOf("phone"), runnable = true)
    private val term = SessionEntry("terminal", "xdg", "Terminal", runnable = true)
    private val dm = SessionEntry("gnome-wayland", "xdg", "GNOME", runnable = false, problem = "no X-Droidc-Renderer")
    private val all = listOf(gnome, dm, phosh, term)

    @Test fun byDeviceTakesTheSessionMadeForIt() {
        assertEquals(phosh, SessionChoice.choose(all, "label", SessionPick(formFactor = "phone")))
        assertEquals(gnome, SessionChoice.choose(all, "label", SessionPick(formFactor = "tablet")))
        // Nothing names a TV: the default doesn't fit, so one that names no device.
        assertEquals(term, SessionChoice.choose(all, "label", SessionPick(formFactor = "tv")))
        // Off: droidc's default everywhere.
        assertEquals(gnome, SessionChoice.choose(all, "label", SessionPick(byDevice = false, formFactor = "phone")))
    }

    @Test fun aChoiceWinsWhileItCanRun() {
        assertEquals(gnome, SessionChoice.choose(all, "label", SessionPick(formFactor = "phone", chosen = "label")))
        // A session that can't start, or is gone, falls back.
        assertEquals(phosh, SessionChoice.choose(all, "label", SessionPick(formFactor = "phone", chosen = "gnome-wayland")))
        assertEquals(phosh, SessionChoice.choose(all, "label", SessionPick(formFactor = "phone", chosen = "gone")))
        assertNull(SessionChoice.choose(emptyList(), null, SessionPick()))
        assertNull(SessionChoice.choose(listOf(dm), null, SessionPick()))
    }

    @Test fun pickerSplitsByShape() {
        val (fits, others) = SessionChoice.split(all, "phone")
        assertEquals(listOf(phosh, term), fits)
        assertEquals(listOf(gnome), others)
    }

    @Test fun deviceClassesNameFormFactors() {
        assertEquals("phone", SessionChoice.formFactor(DeviceClass.Phone))
        assertEquals("tablet", SessionChoice.formFactor(DeviceClass.Foldable))
        assertEquals("xr", SessionChoice.formFactor(DeviceClass.Xr))
    }

    @Test fun shellSpecListsSessions() {
        val line = """{"event":"result","ok":true,"data":{"rootfs":"/r","session":{"id":"label","name":"GNOME",""" +
            """"renderer":"screencast","exec":["gnome-shell"],"services":[],"configured":true,"source":"label"},""" +
            """"sessions":[{"id":"label","source":"label","name":"GNOME","form_factors":["tablet"],"runnable":true,""" +
            """"renderer":"screencast","exec":["gnome-shell"],"services":[],"problem":null},""" +
            """{"id":"gnome-wayland","source":"xdg","name":"GNOME","comment":"Logs you in","desktop_names":["GNOME"],""" +
            """"form_factors":[],"path":"/usr/share/wayland-sessions/gnome-wayland.desktop","runnable":false,""" +
            """"renderer":null,"exec":null,"services":[],"problem":"made for a display manager"}]}}"""
        val r = NdjsonParser.parseLine(line) as DroidcEvent.Result
        val spec = DroidcOutcome.Success(r.data).shellSpec()
        assertEquals("label", spec.session!!.id)
        assertEquals(2, spec.sessions.size)
        assertEquals(listOf("tablet"), spec.sessions[0].formFactors)
        assertEquals("made for a display manager", spec.sessions[1].problem)
        assertEquals(false, spec.sessions[1].runnable)
    }
}
