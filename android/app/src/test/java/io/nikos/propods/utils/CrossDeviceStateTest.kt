package io.nikos.propods.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Source-aware packet handling: ownership is attributed to the exact peer MAC the
 * packet came from. This is the Phase 2 correctness property that lets the courtesy
 * filter and "pods are elsewhere" logic work with 3+ devices — a peer announcing
 * ownership no longer clobbers a single shared flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossDeviceStateTest {

    private val aa = "AA:AA:AA:AA:AA:AA"
    private val bb = "BB:BB:BB:BB:BB:BB"

    @Before
    fun setUp() {
        CrossDevice.resetForTesting()
    }

    @After
    fun tearDown() {
        CrossDevice.resetForTesting()
    }

    @Test
    fun `AIRPODS_CONNECTED adds the source to holders`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)

        assertEquals(setOf(aa), CrossDevice.holders.toSet())
        assertTrue(CrossDevice.isAvailable)
    }

    @Test
    fun `second peer connecting does not clobber the first`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, bb)

        assertEquals(setOf(aa, bb), CrossDevice.holders.toSet())
    }

    @Test
    fun `AIRPODS_DISCONNECTED removes only the source, others remain`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, bb)

        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet, aa)

        assertEquals(setOf(bb), CrossDevice.holders.toSet())
        assertTrue(CrossDevice.isAvailable)
    }

    @Test
    fun `last holder disconnecting clears availability`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet, aa)

        assertTrue(CrossDevice.holders.isEmpty())
        assertFalse(CrossDevice.isAvailable)
    }

    @Test
    fun `relayed AIRPODS_DATA marks the source as holder`() {
        // header + a trivial payload; processRelayedPacket no-ops without a service,
        // but the holder attribution must still happen.
        val packet = CrossDevicePackets.AIRPODS_DATA_HEADER.packet + byteArrayOf(0x01, 0x02)
        CrossDevice.processPacket(packet, aa)

        assertTrue(CrossDevice.holders.contains(aa))
    }

    @Test
    fun `unrecognized packet is ignored without crashing or changing holders`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)

        CrossDevice.processPacket(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F), bb)

        assertEquals(setOf(aa), CrossDevice.holders.toSet())
    }

}
