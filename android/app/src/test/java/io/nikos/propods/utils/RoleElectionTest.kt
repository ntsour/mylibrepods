package io.nikos.propods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-pair role election. The pure [CrossDevice.electClient] predicate decides which
 * side of a pair runs the RFCOMM client; the other stays server-only. Exactly one
 * client per pair avoids the duplicate-channel "read ret: -1" collision.
 */
class RoleElectionTest {

    @Test
    fun `lower name is client, higher name is server-only`() {
        // Pixel < Xiaomi
        assertTrue(CrossDevice.electClient(ownName = "Pixel", peerName = "Xiaomi"))
        assertFalse(CrossDevice.electClient(ownName = "Xiaomi", peerName = "Pixel"))
    }

    @Test
    fun `exactly one side of a pair is client`() {
        val a = "Pixel"
        val b = "Xiaomi"
        // The two devices evaluate the same ordered pair from opposite perspectives.
        assertTrue(CrossDevice.electClient(a, b) != CrossDevice.electClient(b, a))
    }

    @Test
    fun `identical names both stay server-only`() {
        assertFalse(CrossDevice.electClient("Pixel 10", "Pixel 10"))
        // case-insensitive
        assertFalse(CrossDevice.electClient("pixel 10", "PIXEL 10"))
    }

    @Test
    fun `null or blank names stay server-only`() {
        assertFalse(CrossDevice.electClient(null, "Xiaomi"))
        assertFalse(CrossDevice.electClient("Pixel", null))
        assertFalse(CrossDevice.electClient("", "Xiaomi"))
        assertFalse(CrossDevice.electClient("   ", "Xiaomi"))
    }

    @Test
    fun `three peers — device elects client to higher, server-only to lower`() {
        val self = "Bravo"
        val higher = "Charlie"
        val lower = "Alpha"
        assertTrue(CrossDevice.electClient(self, higher))   // client to Charlie
        assertFalse(CrossDevice.electClient(self, lower))   // server-only to Alpha
    }
}
