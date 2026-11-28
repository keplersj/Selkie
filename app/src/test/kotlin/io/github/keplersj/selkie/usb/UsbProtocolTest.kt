package io.github.keplersj.selkie.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbProtocolTest {
    @Test fun readsUsbshimsRequest() {
        assertEquals(UsbProtocol.Parsed.Ok(UsbProtocol.Open(7, 1, 5)),
            UsbProtocol.parse("""{"id":7,"method":"usb.open","params":{"bus":1,"dev":5}}"""))
    }

    @Test fun refusesAnythingElse() {
        assertEquals(UsbProtocol.Parsed.Bad(0, "not a JSON object"), UsbProtocol.parse("hello"))
        assertTrue(UsbProtocol.parse("""{"id":3,"method":"usb.list"}""").let { it is UsbProtocol.Parsed.Bad && it.id == 3L })
        assertTrue(UsbProtocol.parse("""{"id":4,"method":"usb.open","params":{"bus":0,"dev":5}}""") is UsbProtocol.Parsed.Bad)
        assertTrue(UsbProtocol.parse("""{"id":4,"method":"usb.open","params":{"bus":"x","dev":5}}""") is UsbProtocol.Parsed.Bad)
        assertTrue(UsbProtocol.parse("""{"id":4,"method":"usb.open"}""") is UsbProtocol.Parsed.Bad)
        assertTrue(UsbProtocol.parse("[1,2]") is UsbProtocol.Parsed.Bad)
    }

    @Test fun repliesAreOneLineEach() {
        assertEquals("{\"id\":7,\"result\":{}}\n", UsbProtocol.ok(7))
        assertEquals("{\"id\":7,\"error\":{\"kind\":\"gone\",\"message\":\"unplugged\"}}\n",
            UsbProtocol.error(7, UsbProtocol.GONE, "unplugged"))
    }
}
