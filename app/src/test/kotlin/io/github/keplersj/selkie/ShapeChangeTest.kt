package io.github.keplersj.selkie

import android.content.res.Configuration
import io.github.keplersj.selkie.engine.SessionEntry
import io.github.keplersj.selkie.engine.SessionPick
import io.github.keplersj.selkie.engine.ShapeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShapeChangeTest {
    private val phosh = SessionEntry("sm.puri.Phosh", "xdg", "Phosh", formFactors = listOf("phone"), runnable = true)
    private val plasma = SessionEntry("plasma", "xdg", "Plasma Desktop", formFactors = listOf("tablet", "desktop"), runnable = true)
    private val term = SessionEntry("terminal", "xdg", "Terminal", runnable = true)
    private val all = listOf(phosh, plasma, term)

    @Test fun aFoldOpeningUnderPhoshOffersTheTabletSession() {
        assertEquals(plasma, ShapeChange.offer(all, "sm.puri.Phosh", "sm.puri.Phosh", SessionPick(formFactor = "tablet")))
        // Closing it again under Plasma offers Phosh.
        assertEquals(phosh, ShapeChange.offer(all, "sm.puri.Phosh", "plasma", SessionPick(formFactor = "phone")))
    }

    @Test fun nothingToAskWhenTheRunningSessionStillFits() {
        // Plasma suits a tablet and a desktop alike.
        assertNull(ShapeChange.offer(all, null, "plasma", SessionPick(formFactor = "desktop")))
        // A session that names no device suits every one.
        assertNull(ShapeChange.offer(all, null, "terminal", SessionPick(formFactor = "tablet")))
    }

    @Test fun onlyASessionThatSuitsIsOffered() {
        // No session is made for a TV; one that names no device suits it.
        assertEquals(term, ShapeChange.offer(all, null, "sm.puri.Phosh", SessionPick(formFactor = "tv")))
        assertNull(ShapeChange.offer(listOf(phosh), null, "sm.puri.Phosh", SessionPick(formFactor = "tablet")))
        // The user chose Phosh for tablets too: that is what would start, so nothing to offer.
        assertNull(ShapeChange.offer(all, null, "sm.puri.Phosh", SessionPick(formFactor = "tablet", chosen = "sm.puri.Phosh")))
        // Unknown running session (older droidc lists none).
        assertNull(ShapeChange.offer(all, null, null, SessionPick(formFactor = "tablet")))
    }

    @Test fun turningTheDeviceKeepsItsShape() {
        val normal = Configuration.UI_MODE_TYPE_NORMAL
        // A phone's smallest width stays under 600dp in either orientation.
        assertEquals("phone", ShapeChange.shapeOf(normal, 411))
        assertEquals("tablet", ShapeChange.shapeOf(normal, 673))
        assertEquals("tablet", ShapeChange.shapeOf(normal, 800))
        assertEquals("desktop", ShapeChange.shapeOf(Configuration.UI_MODE_TYPE_DESK, 411))
        assertEquals("tv", ShapeChange.shapeOf(Configuration.UI_MODE_TYPE_TELEVISION, 540))
    }
}
