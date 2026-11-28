package io.github.keplersj.selkie.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UsbTreeTest {
    private val pixel = UsbDeviceInfo(bus = 1, dev = 5, vendorId = 0x18d1, productId = 0x4ee0,
        manufacturer = "Google", product = "Pixel 9", serial = "4A1B2C", descriptors = byteArrayOf(18, 1, 0, 2),
        configuration = 1, interfaceNames = mapOf(0 to "fastboot"))

    private fun dirs(): Pair<File, File> = Files.createTempDirectory("usbtree").toFile().let { File(it, "dev") to File(it, "sys") }

    @Test fun deviceNamesGiveBusAndDevice() {
        assertEquals(1 to 5, UsbTree.busDev("/dev/bus/usb/001/005"))
        assertEquals(2 to 127, UsbTree.busDev("/dev/bus/usb/002/127"))
        assertNull(UsbTree.busDev("/dev/bus/usb/001"))
        assertNull(UsbTree.busDev("/dev/ttyACM0"))
        assertEquals("001/005", UsbTree.nodeName(1, 5))
        assertEquals("1-5", UsbTree.sysName(1, 5))
    }

    @Test fun writesTheLayoutFastbootReads() {
        val (dev, sys) = dirs()
        UsbTree.write(dev, sys, pixel)
        val node = File(dev, "001/005")
        assertTrue(node.isFile)
        assertEquals(0, node.length())
        val d = File(sys, "1-5")
        assertEquals("1\n", File(d, "busnum").readText())
        assertEquals("5\n", File(d, "devnum").readText())
        assertEquals("18d1\n", File(d, "idVendor").readText())
        assertEquals("4ee0\n", File(d, "idProduct").readText())
        assertEquals("Google\n", File(d, "manufacturer").readText())
        assertEquals("Pixel 9\n", File(d, "product").readText())
        assertEquals("4A1B2C\n", File(d, "serial").readText())
        assertEquals("1\n", File(d, "bConfigurationValue").readText())
        assertArrayEquals(byteArrayOf(18, 1, 0, 2), File(d, "descriptors").readBytes())
        assertEquals("fastboot\n", File(sys, "1-5:1.0/interface").readText())
    }

    @Test fun missingStringsAreLeftOut() {
        val (dev, sys) = dirs()
        UsbTree.write(dev, sys, pixel.copy(serial = null, manufacturer = null, interfaceNames = emptyMap()))
        assertFalse(File(sys, "1-5/serial").exists())
        assertFalse(File(sys, "1-5/manufacturer").exists())
        assertFalse(File(sys, "1-5:1.0").exists())
    }

    @Test fun aDeviceThatLeavesTheBusIsRemovedAlone() {
        val (dev, sys) = dirs()
        UsbTree.write(dev, sys, pixel)
        UsbTree.write(dev, sys, pixel.copy(dev = 6))
        UsbTree.remove(dev, sys, 1, 5)
        assertFalse(File(dev, "001/005").exists())
        assertFalse(File(sys, "1-5").exists())
        assertFalse(File(sys, "1-5:1.0").exists())
        assertTrue(File(dev, "001/006").isFile)
        assertTrue(File(sys, "1-6/busnum").isFile)
    }

    @Test fun clearEmptiesBothAndKeepsTheBindTargets() {
        val (dev, sys) = dirs()
        UsbTree.write(dev, sys, pixel)
        UsbTree.clear(dev, sys)
        assertTrue(dev.isDirectory && dev.list()!!.isEmpty())
        assertTrue(sys.isDirectory && sys.list()!!.isEmpty())
    }

    @Test fun stringsStayOnOneLine() {
        val (dev, sys) = dirs()
        UsbTree.write(dev, sys, pixel.copy(product = "Pixel\n9 "))
        assertEquals("Pixel 9\n", File(sys, "1-5/product").readText())
    }
}
