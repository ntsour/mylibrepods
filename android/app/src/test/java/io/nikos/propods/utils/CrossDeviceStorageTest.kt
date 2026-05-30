package io.nikos.propods.utils

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossDeviceStorageTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        CrossDevice.resetForTesting()
    }

    @After
    fun tearDown() {
        CrossDevice.resetForTesting()
    }

    // --- Migration tests ---

    @Test
    fun `migration writes cross_device_peers and removes legacy key`() {
        prefs.edit().putString("cross_device_peer_mac", "AA:BB:CC:DD:EE:FF").apply()

        CrossDevice.init(context)

        assertTrue(CrossDevice.configuredPeers.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(prefs.contains("cross_device_peer_mac"))
        assertTrue(prefs.contains("cross_device_peers"))
    }

    @Test
    fun `migration is idempotent — second init does not duplicate`() {
        prefs.edit().putString("cross_device_peer_mac", "AA:BB:CC:DD:EE:FF").apply()
        CrossDevice.init(context)
        CrossDevice.resetForTesting()
        CrossDevice.init(context)

        assertEquals(1, CrossDevice.configuredPeers.size)
        assertEquals("AA:BB:CC:DD:EE:FF", CrossDevice.configuredPeers.first())
    }

    @Test
    fun `migration no-op when no legacy key exists`() {
        CrossDevice.init(context)

        assertTrue(CrossDevice.configuredPeers.isEmpty())
        assertFalse(prefs.contains("cross_device_peers"))
    }

    @Test
    fun `existing cross_device_peers is read without touching legacy key`() {
        prefs.edit()
            .putString("cross_device_peers", """["AA:BB:CC:DD:EE:FF"]""")
            .putString("cross_device_peer_mac", "11:22:33:44:55:66") // should be ignored
            .apply()

        CrossDevice.init(context)

        assertEquals(setOf("AA:BB:CC:DD:EE:FF"), CrossDevice.configuredPeers)
        assertTrue(prefs.contains("cross_device_peer_mac")) // untouched
    }

    // --- holders / isAvailable tests ---

    @Test
    fun `isAvailable true adds configured peer to holders`() {
        CrossDevice.configuredPeers = setOf("AA:BB:CC:DD:EE:FF")

        CrossDevice.isAvailable = true

        assertTrue(CrossDevice.holders.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(CrossDevice.isAvailable)
    }

    @Test
    fun `isAvailable false clears holders`() {
        CrossDevice.configuredPeers = setOf("AA:BB:CC:DD:EE:FF")
        CrossDevice.isAvailable = true

        CrossDevice.isAvailable = false

        assertTrue(CrossDevice.holders.isEmpty())
        assertFalse(CrossDevice.isAvailable)
    }

    @Test
    fun `isAvailable true with no configured peers leaves holders empty`() {
        // configuredPeers is empty after resetForTesting
        CrossDevice.isAvailable = true

        assertTrue(CrossDevice.holders.isEmpty())
        assertFalse(CrossDevice.isAvailable)
    }
}
