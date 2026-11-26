package io.github.keplersj.selkie.ui

import io.github.keplersj.selkie.service.OpKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressModelTest {
    @Test fun droidcPhasesMapToTheFourSteps() {
        val phases = listOf("starting", "resolve", "verify", "download", "unpack", "etc-merge", "deploy", "gc", "ownership")
        assertEquals(
            listOf(0, 0, 0, 1, 2, 3, 3, 3, 3),
            phases.map { InstallStep.of(it).ordinal },
        )
    }

    @Test fun titlesSayWhichContainerAndWhetherItIsNew() {
        assertEquals("Creating work", runningTitle(OpKind.SWITCH, "work", creating = true))
        assertEquals("Changing dev's image", runningTitle(OpKind.SWITCH, "dev", creating = false))
        assertEquals("Couldn't create work", failedTitle(OpKind.SWITCH, "work", creating = true))
        assertEquals("Couldn't update dev", failedTitle(OpKind.UPGRADE, "dev", creating = false))
    }
}
