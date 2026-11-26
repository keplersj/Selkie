package io.github.keplersj.selkie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCatalogTest {
    /** The catalog with Harbor and Monk published, as the design assumes. */
    private val published = ImageCatalog.tuned.map {
        when (it.name) {
            "Harbor" -> it.copy(ref = "ghcr.io/droidc-dev/harbor:stable")
            "Monk" -> it.copy(ref = "ghcr.io/droidc-dev/monk:stable")
            else -> it
        }
    }

    private fun names(d: DeviceClass, v: List<TunedVariant> = published) = ImageCatalog.orderedFor(d, v).map { it.name }

    @Test fun phoneOrderPutsTheFittingVariantFirstThenAnyDevice() {
        assertEquals(listOf("Ringed", "Monk", "Harbor", "Elephant", "Baikal", "Weddell"), names(DeviceClass.Phone))
    }

    @Test fun tabletAndXrOrder() {
        assertEquals(listOf("Harbor", "Monk", "Ringed", "Elephant", "Baikal", "Weddell"), names(DeviceClass.Tablet))
        assertEquals(listOf("Harbor", "Weddell", "Monk", "Ringed", "Elephant", "Baikal"), names(DeviceClass.Xr))
        assertEquals(listOf("Harbor", "Ringed", "Monk", "Elephant", "Baikal", "Weddell"), names(DeviceClass.Foldable))
    }

    @Test fun phonePreselectsMonkOncePublished() {
        assertEquals(ImageSelection.Tuned("Monk"), ImageCatalog.defaultSelection(DeviceClass.Phone, published))
        assertEquals(ImageSelection.Tuned("Harbor"), ImageCatalog.defaultSelection(DeviceClass.Tablet, published))
    }

    @Test fun withNothingPublishedTheSignedBluefinIsPreselected() {
        for (d in DeviceClass.entries) {
            assertEquals(ImageSelection.Known("bluefin"), ImageCatalog.defaultSelection(d))
        }
        assertTrue(ImageCatalog.tuned.none { it.ready })
    }

    @Test fun anUnpublishedVariantResolvesToNothing() {
        assertNull(ImageCatalog.resolve(ImageSelection.Tuned("Monk"), ""))
        assertEquals("ghcr.io/droidc-dev/monk:stable", ImageCatalog.resolve(ImageSelection.Tuned("Monk"), "", published)?.ref)
    }

    @Test fun selectionForCurrentImage() {
        assertEquals(ImageSelection.Known("centos"), ImageCatalog.selectionFor(Images.CENTOS, DeviceClass.Phone))
        assertEquals(ImageSelection.Custom, ImageCatalog.selectionFor("ghcr.io/example/x:1", DeviceClass.Phone))
        assertEquals(ImageSelection.Tuned("Harbor"),
            ImageCatalog.selectionFor("ghcr.io/droidc-dev/harbor:stable", DeviceClass.Phone, published))
    }

    @Test fun centosIsMarkedUnsigned() {
        val r = ImageCatalog.resolve(ImageSelection.Known("centos"), "")!!
        assertFalse(r.signed)
        assertTrue(ImageCatalog.resolve(ImageSelection.Known("bluefin"), "")!!.signed)
    }

    @Test fun customAddresses() {
        assertEquals("image:latest", ImageCatalog.resolve(ImageSelection.Custom, "  registry.example/team/image:latest ")?.title)
        assertNull(ImageCatalog.resolve(ImageSelection.Custom, "not an image"))
        assertNull(ImageCatalog.resolve(ImageSelection.Custom, "latest"))
    }

    @Test fun columnsPerDevice() {
        assertEquals(1, ImageCatalog.columns(DeviceClass.Phone))
        assertEquals(2, ImageCatalog.columns(DeviceClass.Foldable))
        assertEquals(2, ImageCatalog.columns(DeviceClass.Tablet))
        assertEquals(3, ImageCatalog.columns(DeviceClass.Desktop))
        assertEquals(2, ImageCatalog.columns(DeviceClass.Xr))
    }

    @Test fun emptyStateLeadsWithBluefinUntilATunedImageShips() {
        assertEquals("Start with Bluefin LTS, a signed GNOME desktop, or use any other image.",
            ImageCatalog.emptyStateLead(ImageCatalog.tuned.map { it.copy(ref = null) }))
        assertEquals("Start with an image made for Selkie, or use any other.",
            ImageCatalog.emptyStateLead(ImageCatalog.tuned.map { if (it.name == "Monk") it.copy(ref = "ghcr.io/x/monk:1") else it }))
    }
}
