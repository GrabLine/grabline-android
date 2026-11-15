package dev.grabline.app.remote.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetMathTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test fun slash24HasTheOtherHostsOnly() {
        val hosts = SubnetMath.hosts(ip(192, 168, 1, 77), 24)
        assertEquals(253, hosts.size) // 254 hosts minus this phone
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.77" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test fun slash22SpansFourThirdOctets() {
        val hosts = SubnetMath.hosts(ip(10, 0, 5, 9), 22)
        assertEquals(1021, hosts.size) // 1022 hosts minus this phone
        assertEquals("10.0.4.1", hosts.first())
        assertEquals("10.0.7.254", hosts.last())
        assertTrue("10.0.6.0" in hosts) // inside the block, not a network address
        assertFalse("10.0.5.9" in hosts)
        assertFalse("10.0.4.0" in hosts)
        assertFalse("10.0.7.255" in hosts)
    }

    @Test fun widerThanSlash22IsRefused() {
        assertTrue(SubnetMath.hosts(ip(172, 16, 3, 4), 16).isEmpty())
        assertTrue(SubnetMath.hosts(ip(10, 0, 0, 5), 21).isEmpty())
        assertTrue(SubnetMath.hosts(ip(10, 0, 0, 5), 0).isEmpty())
    }

    @Test fun tinySubnetsAndBadInput() {
        assertEquals(listOf("192.168.1.2"), SubnetMath.hosts(ip(192, 168, 1, 3), 31).filter { it == "192.168.1.2" })
        assertTrue(SubnetMath.hosts(ip(192, 168, 1, 3), 32).isEmpty())
        assertEquals(listOf("192.168.1.2"), SubnetMath.hosts(ip(192, 168, 1, 1), 30).filter { it == "192.168.1.2" })
        assertTrue(SubnetMath.hosts(byteArrayOf(1, 2, 3), 24).isEmpty())
    }

    @Test fun addressesAboveTheSignedByteRangeWork() {
        val hosts = SubnetMath.hosts(ip(200, 201, 202, 203), 24)
        assertEquals("200.201.202.1", hosts.first())
    }
}
