package com.musheer360.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyManagerTest {
    private lateinit var keyManager: KeyManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("secure_keys_prefs", 0).edit().clear().commit()
        keyManager = KeyManager(context)
    }

    /** Adds keys and skips the test if Robolectric's KeyStore broke encrypt/decrypt. */
    private fun addKeysAndVerify(vararg keys: String) {
        for (key in keys) keyManager.addKey(key)
        Assume.assumeTrue(
            "KeyStore encryption unusable in test env (stored ${keyManager.getKeys().size} of ${keys.size})",
            keyManager.getKeys().size == keys.size
        )
    }

    // --- addKey / removeKey / getKeys ---

    @Test
    fun getKeys_initiallyEmpty() {
        assertTrue(keyManager.getKeys().isEmpty())
    }

    @Test
    fun addKey_appearsInGetKeys() {
        keyManager.addKey("key1")
        Assume.assumeTrue("KeyStore encryption unusable in test env", keyManager.getKeys().size == 1)
        assertEquals(listOf("key1"), keyManager.getKeys())
    }

    @Test
    fun addKey_duplicateNotAdded() {
        keyManager.addKey("key1")
        keyManager.addKey("key1")
        Assume.assumeTrue("KeyStore encryption unusable in test env", keyManager.getKeys().isNotEmpty())
        assertEquals(1, keyManager.getKeys().size)
    }

    @Test
    fun removeKey_removesIt() {
        keyManager.addKey("key1")
        keyManager.removeKey("key1")
        assertTrue(keyManager.getKeys().isEmpty())
    }

    @Test
    fun removeKey_nonExistent_doesNotCrash() {
        keyManager.removeKey("nonexistent")
    }

    // --- getNextKey (round-robin) ---

    @Test
    fun getNextKey_noKeys_returnsNull() {
        assertNull(keyManager.getNextKey())
    }

    @Test
    fun getNextKey_oneKey_alwaysReturnsThatKey() {
        keyManager.addKey("only")
        Assume.assumeTrue("KeyStore encryption unusable in test env", keyManager.getKeys().isNotEmpty())
        assertEquals("only", keyManager.getNextKey())
        assertEquals("only", keyManager.getNextKey())
        assertEquals("only", keyManager.getNextKey())
    }

    @Test
    fun getNextKey_twoKeys_alternates() {
        addKeysAndVerify("a", "b")
        val first = keyManager.getNextKey()
        val second = keyManager.getNextKey()
        assertNotEquals(first, second)
        assertTrue(setOf(first, second) == setOf("a", "b"))
    }

    @Test
    fun getNextKey_threeKeys_cyclesThroughAll() {
        addKeysAndVerify("a", "b", "c")
        val results = (1..6).map { keyManager.getNextKey() }
        assertTrue(results.containsAll(listOf("a", "b", "c")))
    }

    // --- reportRateLimit ---

    @Test
    fun reportRateLimit_keyIsSkipped() {
        addKeysAndVerify("a", "b")
        keyManager.reportRateLimit("a", 600)
        // All calls should return "b" since "a" is rate-limited
        assertEquals("b", keyManager.getNextKey())
        assertEquals("b", keyManager.getNextKey())
    }

    @Test
    fun reportRateLimit_afterCooldown_keyAvailableAgain() {
        addKeysAndVerify("a")
        keyManager.reportRateLimit("a", 1)
        assertNull(keyManager.getNextKey()) // rate-limited, only key
        Thread.sleep(1100)
        assertEquals("a", keyManager.getNextKey())
    }

    @Test
    fun reportRateLimit_clampedToMax600() {
        addKeysAndVerify("a", "b")
        keyManager.reportRateLimit("a", 9999)
        // "a" should be rate-limited but clamped to 600s, so still skipped now
        assertEquals("b", keyManager.getNextKey())
    }

    // --- markInvalid ---

    @Test
    fun markInvalid_keyIsSkipped() {
        addKeysAndVerify("a", "b")
        keyManager.markInvalid("a")
        assertEquals("b", keyManager.getNextKey())
        assertEquals("b", keyManager.getNextKey())
    }

    @Test
    fun markInvalid_reAddingKeyClearsInvalid() {
        addKeysAndVerify("a")
        keyManager.markInvalid("a")
        assertNull(keyManager.getNextKey())
        keyManager.addKey("a") // re-add clears invalid
        assertEquals("a", keyManager.getNextKey())
    }

    // --- getShortestWaitTimeMs ---

    @Test
    fun getShortestWaitTimeMs_noKeys_returnsNull() {
        assertNull(keyManager.getShortestWaitTimeMs())
    }

    @Test
    fun getShortestWaitTimeMs_noRateLimitedKeys_returnsNull() {
        keyManager.addKey("a")
        assertNull(keyManager.getShortestWaitTimeMs())
    }

    @Test
    fun getShortestWaitTimeMs_returnsShortestWait() {
        addKeysAndVerify("a", "b")
        keyManager.reportRateLimit("a", 10)
        keyManager.reportRateLimit("b", 60)
        val wait = keyManager.getShortestWaitTimeMs()
        assertNotNull(wait)
        // "a" has ~10s wait, "b" has ~60s wait, shortest should be around 10s
        assertTrue(wait!! in 1..10_000)
    }
}
