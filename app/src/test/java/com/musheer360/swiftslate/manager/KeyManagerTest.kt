package com.musheer360.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reversible stand-in for [AndroidKeystoreCipher]. AndroidKeyStore's AES-GCM provider is
 * HAL-backed and unavailable under Robolectric, so these tests previously guarded every
 * assertion with `Assume.assumeTrue(...)` and silently skipped — 11 of 17 cases never ran and
 * CI still reported green over the key storage and rotation logic.
 *
 * Produces the same shape as the real cipher ("<blob>]<blob>", never starting with "[") so the
 * legacy-plaintext detection in [KeyManager] is exercised for real.
 */
private class FakeCipher(override var available: Boolean = true) : KeyCipher {
    var encryptFailures = 0

    override fun encrypt(plainText: String): String {
        if (!available) throw IllegalStateException("unavailable")
        if (encryptFailures > 0) {
            encryptFailures--
            throw IllegalStateException("simulated encrypt failure")
        }
        // Base64, like the real cipher: the payload must not itself contain the "]" separator.
        return "fake]" + java.util.Base64.getEncoder().encodeToString(plainText.toByteArray())
    }

    override fun decrypt(encrypted: String): String? {
        val parts = encrypted.split("]")
        if (parts.size != 2 || parts[0] != "fake") return null
        return try {
            String(java.util.Base64.getDecoder().decode(parts[1]))
        } catch (_: Exception) {
            null
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class KeyManagerTest {
    private lateinit var cipher: FakeCipher
    private lateinit var keyManager: KeyManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("secure_keys_prefs", 0).edit().clear().commit()
        // Key storage is provider-scoped, so the provider selection must start clean too.
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        cipher = FakeCipher()
        keyManager = KeyManager(context, cipher)
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("secure_keys_prefs", 0)

    private fun freshManager(c: KeyCipher = cipher) =
        KeyManager(ApplicationProvider.getApplicationContext<Application>(), c)

    // --- addKey / removeKey / getKeys ---

    @Test
    fun getKeys_initiallyEmpty() {
        assertTrue(keyManager.getKeys().isEmpty())
    }

    @Test
    fun addKey_appearsInGetKeys() {
        assertTrue(keyManager.addKey("key1"))
        assertEquals(listOf("key1"), keyManager.getKeys())
    }

    @Test
    fun addKey_duplicateNotAdded() {
        assertTrue(keyManager.addKey("key1"))
        assertTrue(keyManager.addKey("key1"))
        assertEquals(1, keyManager.getKeys().size)
    }

    @Test
    fun addKey_rejectsBlankAndOverlong() {
        assertFalse(keyManager.addKey(""))
        assertFalse(keyManager.addKey("   "))
        assertFalse(keyManager.addKey("a".repeat(257)))
        assertTrue(keyManager.getKeys().isEmpty())
    }

    @Test
    fun removeKey_removesIt() {
        keyManager.addKey("key1")
        assertTrue(keyManager.removeKey("key1"))
        assertTrue(keyManager.getKeys().isEmpty())
    }

    @Test
    fun removeKey_nonExistent_doesNotCrash() {
        keyManager.addKey("key1")
        keyManager.removeKey("nope")
        assertEquals(listOf("key1"), keyManager.getKeys())
    }

    @Test
    fun addKey_preservesInsertionOrder() {
        keyManager.addKey("a"); keyManager.addKey("b"); keyManager.addKey("c")
        assertEquals(listOf("a", "b", "c"), keyManager.getKeys())
    }

    // --- storage / encryption ---

    @Test
    fun keysAreNotStoredInPlaintext() {
        keyManager.addKey("super-secret-key")
        val stored = prefs().getString("keys_array_gemini", null)
        assertNotNull(stored)
        assertFalse("raw key must not appear in prefs", stored!!.contains("super-secret-key"))
    }

    @Test
    fun keysSurviveANewManagerInstance() {
        keyManager.addKey("k1"); keyManager.addKey("k2")
        assertEquals(listOf("k1", "k2"), freshManager().getKeys())
    }

    @Test
    fun undecryptableBlob_yieldsEmptyListInsteadOfCrashing() {
        prefs().edit().putString("keys_array_gemini", "garbage]not-ours").commit()
        assertTrue(freshManager().getKeys().isEmpty())
    }

    /**
     * Keys are scoped per provider: a Command Code key must not be offered to Gemini, and
     * switching back must still find the Gemini pool.
     */
    @Test
    fun keysAreScopedPerProvider() {
        val settings = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("settings", 0)
        keyManager.addKey("gemini-key")

        settings.edit().putString("provider_type", "commandcode").commit()
        assertTrue("another provider must start empty", freshManager().getKeys().isEmpty())
        assertTrue(freshManager().addKey("command-key"))

        settings.edit().putString("provider_type", "gemini").commit()
        assertEquals(listOf("gemini-key"), freshManager().getKeys())
        settings.edit().putString("provider_type", "commandcode").commit()
        assertEquals(listOf("command-key"), freshManager().getKeys())
    }

    /**
     * Same instance, provider switched between calls: the 5s cache must not serve the previous
     * provider's keys. A regression here sent one vendor's key to another vendor's endpoint.
     */
    @Test
    fun switchingProvider_invalidatesCachedKeysWithinSameInstance() {
        val settings = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("settings", 0)
        keyManager.addKey("gemini-key")
        assertEquals(listOf("gemini-key"), keyManager.getKeys()) // now cached

        settings.edit().putString("provider_type", "commandcode").commit()
        assertTrue("must not reuse the Gemini cache", keyManager.getKeys().isEmpty())
        assertTrue(keyManager.addKey("cmd-key"))
        assertEquals(listOf("cmd-key"), keyManager.getKeys())
    }

    /**
     * The legacy single pool predates per-provider storage. Before this change the whole pool
     * was used by whichever provider was active, so that provider keeps it — but only the three
     * providers that existed then, never one added later.
     */
    @Test
    fun legacyPool_migratesToTheActiveLegacyProvider() {
        val settings = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("settings", 0)
        prefs().edit().putString("keys_array", JSONArray(listOf("old1")).toString()).commit()
        settings.edit().putString("provider_type", "groq").commit()

        assertEquals(listOf("old1"), freshManager().getKeys())
        assertNull(prefs().getString("keys_array", null))

        // Adopted exactly once: Gemini must not get a second copy.
        settings.edit().putString("provider_type", "gemini").commit()
        assertTrue(freshManager().getKeys().isEmpty())
    }

    @Test
    fun legacyPool_isNotAdoptedByProvidersAddedLater() {
        val settings = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("settings", 0)
        prefs().edit().putString("keys_array", JSONArray(listOf("old1")).toString()).commit()
        settings.edit().putString("provider_type", "deepseek").commit()

        assertTrue("legacy keys must not be handed to a provider that did not exist", freshManager().getKeys().isEmpty())
        assertNotNull("legacy slot stays pending", prefs().getString("keys_array", null))
    }

    /** The UI always passes an explicit provider; the default argument must not override it. */
    @Test
    fun explicitProviderArg_overridesStoredSelection() {
        val settings = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("settings", 0)
        settings.edit().putString("provider_type", "gemini").commit()

        keyManager.addKey("k-cmd", "commandcode")
        assertEquals(listOf("k-cmd"), keyManager.getKeys("commandcode"))
        assertTrue(keyManager.getKeys("gemini").isEmpty())
    }

    @Test
    fun removeKey_isScopedToProvider() {
        keyManager.addKey("g-key", "gemini")
        keyManager.addKey("c-key", "commandcode")

        assertTrue(keyManager.removeKey("c-key", "commandcode"))
        assertTrue(keyManager.getKeys("commandcode").isEmpty())
        assertEquals(listOf("g-key"), keyManager.getKeys("gemini"))
    }

    @Test
    fun saveFailure_isReportedAndDoesNotCacheAStaleList() {
        keyManager.addKey("k1")
        cipher.encryptFailures = 1
        assertFalse(keyManager.addKey("k2"))
        // k2 must not appear just because the in-memory list was mutated before the failed write.
        assertEquals(listOf("k1"), freshManager().getKeys())
    }

    @Test
    fun keystoreUnavailable_isSurfaced() {
        assertTrue(keyManager.keystoreAvailable)
        assertFalse(freshManager(FakeCipher(available = false)).keystoreAvailable)
    }

    // --- legacy plaintext migration ---

    @Test
    fun isLegacyPlaintext_recognisesAJsonArrayContainingTheSeparator() {
        // Regression: the old check was !contains("]"), and every legacy value contains "]".
        assertTrue(KeyManager.isLegacyPlaintext("""["k1","k2"]"""))
        assertTrue(KeyManager.isLegacyPlaintext("""  ["k1"]"""))
        assertFalse(KeyManager.isLegacyPlaintext("fake]abc"))
        assertFalse(KeyManager.isLegacyPlaintext("AAAA]BBBB"))
    }

    @Test
    fun legacyPlaintextKeys_areReadAndReEncrypted() {
        prefs().edit().putString("keys_array", JSONArray(listOf("old1", "old2")).toString()).commit()
        val migrated = freshManager()
        assertEquals(listOf("old1", "old2"), migrated.getKeys())
        // The pre-provider pool is adopted by the active provider (Gemini by default) and the
        // old single slot is cleared so it can never be migrated twice.
        val stored = prefs().getString("keys_array_gemini", null)!!
        assertFalse("must have been re-encrypted", KeyManager.isLegacyPlaintext(stored))
        assertFalse(stored.contains("old1"))
        assertNull(prefs().getString("keys_array", null))
        // And still readable afterwards.
        assertEquals(listOf("old1", "old2"), freshManager().getKeys())
    }

    @Test
    fun legacyPlaintextKeys_stillReadableWhenReEncryptionFails() {
        prefs().edit().putString("keys_array", JSONArray(listOf("old1")).toString()).commit()
        assertEquals(listOf("old1"), freshManager(FakeCipher(available = false)).getKeys())
    }

    // --- getNextKey ---

    @Test
    fun getNextKey_noKeys_returnsNull() {
        assertNull(keyManager.getNextKey())
    }

    @Test
    fun getNextKey_oneKey_alwaysReturnsThatKey() {
        keyManager.addKey("only")
        repeat(5) { assertEquals("only", keyManager.getNextKey()) }
    }

    @Test
    fun getNextKey_cyclesThroughAll() {
        keyManager.addKey("a"); keyManager.addKey("b"); keyManager.addKey("c")
        val seen = (1..6).mapNotNull { keyManager.getNextKey() }.toSet()
        assertEquals(setOf("a", "b", "c"), seen)
    }

    @Test
    fun getNextKey_skipsAlreadyTried() {
        keyManager.addKey("a"); keyManager.addKey("b")
        assertEquals("b", keyManager.getNextKey(alreadyTried = setOf("a")))
        assertNull(keyManager.getNextKey(alreadyTried = setOf("a", "b")))
    }

    /**
     * Regression for the shrinking-list modulo bug: every attempt must land on a key that has
     * not been tried, so a healthy key is never left idle while the command fails.
     */
    @Test
    fun getNextKey_neverRepeatsWhenCallerAccumulatesTriedKeys() {
        keyManager.addKey("a"); keyManager.addKey("b"); keyManager.addKey("c")
        val tried = mutableSetOf<String>()
        repeat(3) {
            val next = keyManager.getNextKey(tried)
            assertNotNull(next)
            assertFalse("returned an already-tried key: $next", next in tried)
            tried.add(next!!)
        }
        assertEquals(setOf("a", "b", "c"), tried)
        assertNull(keyManager.getNextKey(tried))
    }

    // --- rate limiting / invalid marks ---

    @Test
    fun reportRateLimit_keyIsSkipped() {
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.reportRateLimit("a", 60)
        repeat(4) { assertEquals("b", keyManager.getNextKey()) }
    }

    @Test
    fun reportRateLimit_afterCooldown_keyAvailableAgain() {
        keyManager.addKey("a")
        keyManager.reportRateLimit("a", 1)
        assertNull(keyManager.getNextKey())
        Thread.sleep(1100)
        assertEquals("a", keyManager.getNextKey())
    }

    @Test
    fun reportRateLimit_clampedToMax600() {
        keyManager.addKey("a")
        keyManager.reportRateLimit("a", 99_999)
        val wait = keyManager.getShortestWaitTimeMs()!!
        assertTrue("clamped to 600s, was ${wait}ms", wait in 590_000..600_000)
    }

    @Test
    fun reportRateLimit_clampedToMin1() {
        keyManager.addKey("a")
        keyManager.reportRateLimit("a", 0)
        assertTrue(keyManager.getShortestWaitTimeMs()!! in 1..1_000)
    }

    @Test
    fun markInvalid_keyIsSkipped() {
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.markInvalid("a")
        repeat(4) { assertEquals("b", keyManager.getNextKey()) }
    }

    @Test
    fun markInvalid_allKeys_returnsNull() {
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.markInvalid("a"); keyManager.markInvalid("b")
        assertNull(keyManager.getNextKey())
    }

    @Test
    fun markInvalid_reAddingKeyClearsInvalid() {
        keyManager.addKey("a")
        keyManager.markInvalid("a")
        assertNull(keyManager.getNextKey())
        assertTrue(keyManager.addKey("a"))
        assertEquals("a", keyManager.getNextKey())
    }

    @Test
    fun clearMarks_releasesInvalidAndRateLimitedKeys() {
        keyManager.addKey("a")
        keyManager.markInvalid("a")
        keyManager.reportRateLimit("a", 60)
        assertNull(keyManager.getNextKey())
        keyManager.clearMarks("a")
        assertEquals("a", keyManager.getNextKey())
    }

    @Test
    fun clearMarks_unknownKey_isANoOp() {
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.clearMarks("never-added")
        val seen = (1..4).mapNotNull { keyManager.getNextKey() }.toSet()
        assertEquals(setOf("a", "b"), seen)
    }

    @Test
    fun removingKey_clearsItsRateLimitAndInvalidMark() {
        keyManager.addKey("a")
        keyManager.reportRateLimit("a", 600)
        keyManager.markInvalid("a")
        keyManager.removeKey("a")
        keyManager.addKey("a")
        assertEquals("a", keyManager.getNextKey())
        assertNull(keyManager.getShortestWaitTimeMs())
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
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.reportRateLimit("a", 300)
        keyManager.reportRateLimit("b", 30)
        val wait = keyManager.getShortestWaitTimeMs()!!
        assertTrue("expected ~30s, was ${wait}ms", wait in 25_000..30_000)
    }

    /** An invalid key's cooldown must not be offered as the wait time — it will not recover. */
    @Test
    fun getShortestWaitTimeMs_ignoresInvalidKeys() {
        keyManager.addKey("a"); keyManager.addKey("b")
        keyManager.reportRateLimit("a", 30)
        keyManager.reportRateLimit("b", 300)
        keyManager.markInvalid("a")
        val wait = keyManager.getShortestWaitTimeMs()!!
        assertTrue("expected ~300s, was ${wait}ms", wait in 290_000..300_000)
    }
}
