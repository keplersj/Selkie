package io.github.keplersj.selkie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCatalogTest {
    /** The catalog with its published images trusted, as the design assumes. */
    private val published = ImageCatalog.tuned.map { if (it.ready) it.copy(signed = true) else it }

    private fun names(d: DeviceClass, v: List<TunedVariant> = published) = ImageCatalog.orderedFor(d, v).map { it.name }

    @Test fun phoneOrderPutsTheFittingVariantFirstThenAnyDevice() {
        assertEquals(
            listOf("Hooded", "Ringed", "Monk", "Harbor", "Elephant", "Baikal", "Weddell"),
            names(DeviceClass.Phone),
        )
    }

    @Test fun tabletAndXrOrder() {
        assertEquals(
            listOf("Harbor", "Monk", "Hooded", "Ringed", "Elephant", "Baikal", "Weddell"),
            names(DeviceClass.Tablet),
        )
        assertEquals(
            listOf("Harbor", "Weddell", "Monk", "Hooded", "Ringed", "Elephant", "Baikal"),
            names(DeviceClass.Xr),
        )
        assertEquals(
            listOf("Harbor", "Hooded", "Ringed", "Monk", "Elephant", "Baikal", "Weddell"),
            names(DeviceClass.Foldable),
        )
    }

    @Test fun onceTrustedTheFittingImageIsPreselected() {
        assertEquals(ImageSelection.Tuned("Hooded"), ImageCatalog.defaultSelection(DeviceClass.Phone, published))
        assertEquals(ImageSelection.Tuned("Harbor"), ImageCatalog.defaultSelection(DeviceClass.Tablet, published))
        assertEquals(ImageSelection.Tuned("Monk"), ImageCatalog.defaultSelection(DeviceClass.Tv, published))
    }

    @Test fun withNoSignedTunedImageTheSignedBluefinIsPreselected() {
        val nothing = ImageCatalog.tuned.map { it.copy(ref = null) }
        for (d in DeviceClass.entries) {
            assertEquals(ImageSelection.Known("bluefin"), ImageCatalog.defaultSelection(d, nothing))
            // Harbor, Monk and Hooded are published but unsigned, so they can be chosen and are never preselected.
            assertEquals(ImageSelection.Known("bluefin"), ImageCatalog.defaultSelection(d))
        }
    }

    @Test fun pinnipedOsDroidcImagesArePublished() {
        val refs = ImageCatalog.tuned.filter { it.ready }.associate { it.name to it.ref }
        assertEquals(mapOf(
            "Harbor" to "ghcr.io/pinniped-os/harbor:45-droidc",
            "Monk" to "ghcr.io/pinniped-os/monk:45-droidc",
            "Hooded" to "ghcr.io/pinniped-os/hooded:45-droidc",
        ), refs)
    }

    @Test fun anUnpublishedVariantResolvesToNothing() {
        assertNull(ImageCatalog.resolve(ImageSelection.Tuned("Ringed"), ""))
        assertEquals("ghcr.io/pinniped-os/hooded:45-droidc",
            ImageCatalog.resolve(ImageSelection.Tuned("Hooded"), "")?.ref)
    }

    @Test fun selectionForCurrentImage() {
        assertEquals(ImageSelection.Known("centos"), ImageCatalog.selectionFor(Images.CENTOS, DeviceClass.Phone))
        assertEquals(ImageSelection.Custom, ImageCatalog.selectionFor("ghcr.io/example/x:1", DeviceClass.Phone))
        assertEquals(ImageSelection.Tuned("Harbor"),
            ImageCatalog.selectionFor("ghcr.io/pinniped-os/harbor:45-droidc", DeviceClass.Phone))
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

    @Test fun pinnipedDroidcRefsFollowTheFactoryTagScheme() {
        assertEquals("ghcr.io/pinniped-os/monk:45-droidc", PinnipedOs.droidcRef("monk"))
        assertEquals("ghcr.io/pinniped-os/harbor:45-droidc", PinnipedOs.droidcRef("harbor"))
        assertEquals("ghcr.io/pinniped-os/hooded:45-droidc", PinnipedOs.droidcRef("hooded"))
    }

    @Test fun tunedVariantsAreUnsignedUntilDroidcTrustsThem() {
        assertTrue(ImageCatalog.tuned.none { it.signed })
        for (name in listOf("Harbor", "Monk", "Hooded")) {
            assertFalse(ImageCatalog.resolve(ImageSelection.Tuned(name), "")!!.signed)
        }
        assertTrue(ImageCatalog.resolve(ImageSelection.Tuned("Monk"), "", published)!!.signed)
    }
}
