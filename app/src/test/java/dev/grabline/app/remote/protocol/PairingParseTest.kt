package dev.grabline.app.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingParseTest {
    private val pin = "Le1QbEwBSOkh7PZtV1-1iOmkqUK0eHKEuxFG5_5LFg4"
    private val pcId = "A3D7Za2aw26jbGofVV3ZAQ"
    private val good = "grabline-pair:?v=1&a=192.168.1.20,10.8.0.3,%5Bfd7a:115c:a1e0::20%5D&p=47821&f=$pin&c=7K3M9QX2RTBW&i=$pcId&n=Study%20PC&x=1794700120"

    private fun valid(text: String) = (PairingUri.parse(text) as PairingUriResult.Valid).data
    private fun error(text: String) = (PairingUri.parse(text) as PairingUriResult.Invalid).error

    @Test
    fun parsesAGoodUri() {
        val d = valid(good)
        assertEquals(3, d.addresses.size)
        assertEquals("[fd7a:115c:a1e0::20]", d.addresses[2])
        assertEquals("Study PC", d.pcName)
        assertFalse(d.isExpired(1794700119))
        assertTrue(d.isExpired(1794700120))
    }

    @Test
    fun parametersMayComeInAnyOrderAndUnknownOnesAreIgnored() {
        val reordered = "grabline-pair:?x=1794700120&future=zzz&n=Study%20PC&i=$pcId&c=7K3M9QX2RTBW&f=$pin&p=47821&a=192.168.1.20&v=1"
        assertEquals(listOf("192.168.1.20"), valid(reordered).addresses)
    }

    @Test
    fun nameIsPercentDecodedAsUtf8() {
        assertEquals("Büro-PC", valid(good.replace("Study%20PC", "B%C3%BCro-PC")).pcName)
    }

    @Test
    fun unknownVersionAsksForAnUpdate() {
        assertEquals(PairingParseError.UPDATE_THIS_APP, error(good.replace("v=1", "v=2")))
        assertEquals("Update GrabLine on this phone", PairingParseError.UPDATE_THIS_APP.userMessage)
    }

    @Test
    fun anyMissingOrMalformedParameterIsNotAPairingCode() {
        val cases = listOf(
            "https://example.com",
            "grabline-pair:",
            good.replace("v=1&", ""),
            good.replace("a=192.168.1.20,10.8.0.3,%5Bfd7a:115c:a1e0::20%5D", "a="),
            good.replace("192.168.1.20", "999.1.1.1"),
            good.replace("%5Bfd7a:115c:a1e0::20%5D", "fd7a:115c:a1e0::20"), // IPv6 needs brackets
            good.replace("%5Bfd7a:115c:a1e0::20%5D", "%5Bfe80::1%25eth0%5D"), // zone id
            good.replace("a=192.168.1.20", "a=" + (1..9).joinToString(",") { "10.0.0.$it" } + ",192.168.1.20"), // 10 entries
            good.replace("p=47821", "p=80"),
            good.replace("p=47821", "p=70000"),
            good.replace("p=47821", "p=abc"),
            good.replace(pin, pin.dropLast(1)),
            good.replace(pin, pin.dropLast(1) + "="),
            good.replace("7K3M9QX2RTBW", "7K3M9QX2RTB"),
            good.replace("7K3M9QX2RTBW", "7K3M9QX2RTBU"), // U is not in the alphabet
            good.replace("7K3M9QX2RTBW", "7k3m9qx2rtbw"), // must be as shown by the PC
            good.replace(pcId, pcId.dropLast(2)),
            good.replace("n=Study%20PC", "n="),
            good.replace("n=Study%20PC", "n=" + "a".repeat(65)),
            good.replace("n=Study%20PC", "n=bad%0Aname"),
            good.replace("n=Study%20PC", "n=%FF"), // invalid UTF-8
            good.replace("n=Study%20PC", "n=bad%2"),
            good.replace("x=1794700120", "x=soon"),
            "$good&p=47822", // duplicate known parameter
        )
        for (c in cases) assertEquals(c, PairingParseError.NOT_A_PAIRING_CODE, error(c))
    }

    @Test
    fun theCodeNeverAppearsInToString() {
        assertFalse(valid(good).toString().contains("7K3M9QX2RTBW"))
        val m = (ManualPairing.parse("192.168.1.20:47821", "7K3M-9QX2-RTBW") as ManualPairingResult.Valid).data
        assertFalse(m.toString().contains("7K3M9QX2RTBW"))
    }

    // ------------------------------------------------------------- manual

    private fun manual(address: String, code: String) = ManualPairing.parse(address, code)

    @Test
    fun manualNormalisesTheCode() {
        val d = (manual("192.168.1.20:47821", " 7k3m-9qx2 rtbw ") as ManualPairingResult.Valid).data
        assertEquals("7K3M9QX2RTBW", d.code)
        assertEquals("011100000000", CrockfordCode.normalize("OIL1 0000 0000"))
        assertNull(CrockfordCode.normalize("7K3M-9QX2-RTB")) // 11 characters
        assertNull(CrockfordCode.normalize("7K3M-9QX2-RTBU")) // U
    }

    @Test
    fun manualAddressForms() {
        fun addr(s: String) = ManualPairing.parseAddress(s)
        assertEquals("192.168.1.20" to 47821, addr("192.168.1.20:47821"))
        assertEquals("192.168.1.20" to DEFAULT_PORT, addr("192.168.1.20")) // port optional
        assertEquals("[fd7a:115c:a1e0::20]" to 5000, addr("[fd7a:115c:a1e0::20]:5000"))
        assertEquals("[fd7a:115c:a1e0::20]" to DEFAULT_PORT, addr("[fd7a:115c:a1e0::20]"))
        assertEquals("study-pc.local" to 47821, addr("study-pc.local:47821"))
        assertNull(addr("fd7a:115c:a1e0::20:5000")) // IPv6 without brackets
        assertNull(addr("192.168.1.20:80"))
        assertNull(addr("192.168.1.20:"))
        assertNull(addr("192.168.1.20:99999"))
        assertNull(addr("999.1.1.1"))
        assertNull(addr(""))
        assertNull(addr("[fd7a::1"))
        assertNull(addr("[fe80::1%25eth0]:5000"))
        assertNull(addr("bad host:5000"))
    }

    @Test
    fun manualErrorsUsePlainWords() {
        assertEquals(PairingParseError.BAD_ADDRESS, (manual("nope!", "7K3M9QX2RTBW") as ManualPairingResult.Invalid).error)
        assertEquals(PairingParseError.BAD_CODE, (manual("192.168.1.20", "123") as ManualPairingResult.Invalid).error)
        assertEquals("That code doesn't look right", PairingParseError.BAD_CODE.userMessage)
        assertEquals(PairingParseError.BAD_CODE, (ManualPairing.parse("192.168.1.20:47821") as ManualPairingResult.Invalid).error)
    }

    @Test
    fun manualTextWithOneLineOrTwo() {
        assertTrue(ManualPairing.parse("192.168.1.20:47821 7K3M-9QX2-RTBW") is ManualPairingResult.Valid)
        assertTrue(ManualPairing.parse("192.168.1.20:47821\n7K3M-9QX2-RTBW\n") is ManualPairingResult.Valid)
        assertTrue(ManualPairing.parse("192.168.1.20:47821\r\n7K3M 9QX2 RTBW") is ManualPairingResult.Valid)
    }
}
