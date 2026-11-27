package io.github.keplersj.selkie.ui

import android.view.InputDevice
import io.github.keplersj.selkie.service.DesktopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvModelTest {
    private val remote = InputInfo(InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD)
    private val gamepad = InputInfo(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)
    private val keyboard = InputInfo(InputDevice.SOURCE_KEYBOARD, alphabetic = true)
    private val mouse = InputInfo(InputDevice.SOURCE_MOUSE)

    @Test fun aRemoteAloneIsNoKeyboardOrPointer() =
        assertEquals(TvInputs(), tvInputs(listOf(remote)))

    @Test fun gamepadKeyboardAndMouseAreEachSeen() =
        assertEquals(TvInputs(gamepad = true, keyboard = true, pointer = true),
            tvInputs(listOf(remote, gamepad, keyboard, mouse)))

    @Test fun aTrackpadIsAPointer() =
        assertEquals(TvInputs(pointer = true), tvInputs(listOf(InputInfo(InputDevice.SOURCE_TOUCHPAD))))

    @Test fun virtualDevicesAreIgnored() =
        assertEquals(TvInputs(), tvInputs(listOf(keyboard.copy(virtual = true), mouse.copy(virtual = true))))

    @Test fun theRunningDesktopIsFeaturedOverTheSelectedContainer() {
        val s = Fixtures.homeContainers
        assertEquals("default", tvFeatured(s, summaries(s))?.name)
    }

    @Test fun withNothingRunningTheSelectedContainerIsFeatured() {
        val s = Fixtures.homeContainers.copy(desktop = DesktopState.Stopped, desktopContainer = null)
        assertEquals("dev", tvFeatured(s, summaries(s))?.name)
    }

    @Test fun nothingIsFeaturedWithoutContainers() =
        assertNull(tvFeatured(Fixtures.homeEmpty, summaries(Fixtures.homeEmpty)))

    @Test fun stateLinesNameTheImageAndWhatRuns() {
        val c = ContainerSummary("dev", Images.CENTOS, terminals = 2, desktop = false)
        assertEquals("CentOS Stream 10 · 2 terminals", tvStateLine(c, starting = false))
        assertEquals("Bluefin LTS · desktop starting",
            tvStateLine(ContainerSummary("default", Images.BLUEFIN, 0, desktop = true), starting = true))
        assertEquals("Bluefin LTS · stopped", tvStateLine(ContainerSummary("x", Images.BLUEFIN, 0, false), false))
    }
}
