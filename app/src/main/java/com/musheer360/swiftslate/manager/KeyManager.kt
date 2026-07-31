package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

class KeyManager internal constructor(
    context: Context,
    private val cipher: KeyCipher
) {
    constructor(context: Context) : this(context, AndroidKeystoreCipher())

    private val prefs: SharedPreferences = context.getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_KEY_ARRAY = "keys_array"
        private const val CACHE_TTL_MS = 5_000L
        private const val MAX_KEY_LENGTH = 256
        // Invalid-key marks expire. A 403 is not always the key's fault (e.g. selecting a
        // model the key's project can't access returns 403 for every key), and marks used
        // to last for the whole process lifetime — so one bad model choice permanently
        // killed every key with no recovery except re-adding them all.
        private const val INVALID_KEY_TTL_MS = 900_000L // 15 min

        /**
         * Whether [stored] is a pre-encryption plaintext JSON array rather than ciphertext.
         *
         * This used to be `!stored.contains("]")`, which is wrong: the legacy format is a JSON
         * array, and `["key"]` contains that separator. Legacy values were therefore routed
         * straight to decrypt(), which split them on "]", failed, and made getKeys() return —
         * and cache — an empty list. Anyone upgrading from a plaintext build silently lost every
         * key. Ciphertext is "<base64>]<base64>" and base64 never starts with "[", so the two
         * shapes are unambiguous.
         */
        internal fun isLegacyPlaintext(stored: String): Boolean = stored.trimStart().startsWith("[")
    }

    private val rateLimitedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** key -> timestamp after which the invalid mark is forgotten. */
    private val invalidKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val roundRobinIndex = AtomicInteger(0)
    @Volatile
    private var cachedKeys: List<String>? = null
    @Volatile
    private var cacheTimestamp = 0L
    /**
     * Ciphertext [cachedKeys] was decrypted from, so an expired TTL can be revalidated with a
     * string compare instead of another AndroidKeyStore round trip + AES-GCM decrypt. The TTL
     * itself has to stay: the UI and the accessibility service hold separate KeyManager
     * instances in one process, and this is how one notices the other's writes.
     */
    @Volatile
    private var cachedCipherText: String? = null

    val keystoreAvailable: Boolean get() = cipher.available

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    @Synchronized
    fun getKeys(): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedKeys
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val stored = prefs.getString(PREF_KEY_ARRAY, null) ?: return emptyList()
        // TTL expired but the stored ciphertext is byte-identical, so the plaintext cannot have
        // changed: revalidate the cache rather than paying for another KeyStore decrypt. Without
        // this, every 5s of active typing re-ran AES-GCM on the accessibility service's path.
        if (cached != null && stored == cachedCipherText) {
            cacheTimestamp = now
            return cached
        }
        // Legacy plaintext migration — can be removed once all users are on an encrypted build.
        if (isLegacyPlaintext(stored)) {
            return try {
                val encrypted = cipher.encrypt(stored)
                prefs.edit().putString(PREF_KEY_ARRAY, encrypted).commit()
                val list = JSONArray(stored).toStringList()
                cacheKeys(list, encrypted)
                list
            } catch (_: Exception) {
                // Encryption failed (e.g. keystore invalidated) — return the plaintext keys so
                // the user does not lose access, without caching or rewriting anything.
                try { JSONArray(stored).toStringList() } catch (_: Exception) { emptyList() }
            }
        }
        val jsonStr = cipher.decrypt(stored) ?: run {
            cacheKeys(emptyList(), stored)
            return emptyList()
        }
        val list = try { JSONArray(jsonStr).toStringList() } catch (_: Exception) { emptyList() }
        cacheKeys(list, stored)
        return list
    }

    private fun cacheKeys(keys: List<String>, cipherText: String?) {
        cachedKeys = keys
        cachedCipherText = cipherText
        cacheTimestamp = System.currentTimeMillis()
    }

    @Synchronized
    private fun saveKeys(keys: List<String>): Boolean {
        val arr = JSONArray(keys)
        return try {
            val cipherText = cipher.encrypt(arr.toString())
            prefs.edit().putString(PREF_KEY_ARRAY, cipherText).apply()
            cacheKeys(keys, cipherText)
            true
        } catch (_: Exception) {
            // Invalidate: the stored value and the in-memory list may now disagree.
            cachedKeys = null
            cachedCipherText = null
            cacheTimestamp = 0L
            false
        }
    }

    @Synchronized
    fun addKey(key: String): Boolean {
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) return false
        val keys = getKeys().toMutableList()
        if (!keys.contains(key)) {
            keys.add(key)
            if (!saveKeys(keys)) return false
        }
        invalidKeys.remove(key)
        return true
    }

    @Synchronized
    fun removeKey(key: String): Boolean {
        val keys = getKeys().toMutableList()
        keys.remove(key)
        val saved = saveKeys(keys)
        rateLimitedKeys.remove(key)
        invalidKeys.remove(key)
        return saved
    }

    // All @Synchronized methods use `this` as monitor (reentrant).
    // getNextKey() intentionally calls getKeys() while holding the lock.
    /**
     * Next usable key, skipping benched ones and anything in [alreadyTried].
     *
     * [alreadyTried] exists because a monotonic round-robin index modulo a *shrinking* list is
     * not a permutation: with keys [A,B,C] and the counter at 1, a 5xx on B followed by a 429 on
     * C mapped attempt 3 back to B — re-sending a byte-identical request while A was never tried
     * at all, so the command could fail with a healthy key sitting idle.
     */
    @Synchronized
    fun getNextKey(alreadyTried: Set<String> = emptySet()): String? {
        val keys = getKeys()
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (key in alreadyTried) return@filter false
            if (isInvalid(key)) return@filter false
            val limitTime = rateLimitedKeys[key] ?: 0L
            now > limitTime
        }

        if (validKeys.isEmpty()) return null

        val idx = (roundRobinIndex.getAndIncrement() and Int.MAX_VALUE) % validKeys.size
        return validKeys[idx]
    }

    fun reportRateLimit(key: String, retryAfterSeconds: Long = 60) {
        val cooldown = retryAfterSeconds.coerceIn(1, 600)
        rateLimitedKeys[key] = System.currentTimeMillis() + cooldown * 1_000
    }

    fun markInvalid(key: String) {
        invalidKeys[key] = System.currentTimeMillis() + INVALID_KEY_TTL_MS
    }

    /**
     * Whether [key] is currently benched as invalid, expiring the mark if it is due.
     * Self-healing: without expiry a transient 403 killed the key until the process
     * restarted (see [INVALID_KEY_TTL_MS]).
     */
    private fun isInvalid(key: String): Boolean {
        val until = invalidKeys[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            invalidKeys.remove(key)
            return false
        }
        return true
    }

    fun getShortestWaitTimeMs(): Long? {
        val keys = getKeys()
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val waits = keys.filter { !isInvalid(it) }
            .mapNotNull { key ->
                val limitTime = rateLimitedKeys[key] ?: return@mapNotNull null
                val remaining = limitTime - now
                if (remaining > 0) remaining else null
            }
        return waits.minOrNull()
    }
}
