package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "typeslate_secure_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SEPARATOR = "]"
        private const val PREF_KEY_ARRAY = "keys_array"
    }

    private val rateLimitedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val invalidKeys: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val roundRobinIndex = AtomicInteger(0)
    @Volatile
    private var cachedKeys: List<String>? = null
    @Volatile
    var keystoreAvailable: Boolean = true
        private set

    init {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            android.util.Log.e("KeyManager", "Keystore init failed", e)
            keystoreAvailable = false
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            android.util.Log.e("KeyManager", "Failed to get secret key", e)
            null
        }
    }

    private fun encrypt(plainText: String): String {
        val secretKey = getSecretKey()
            ?: throw IllegalStateException("Keystore unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherTextString = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        return "$ivString$IV_SEPARATOR$cipherTextString"
    }

    private fun decrypt(encryptedString: String): String? {
        // TODO(v1.2): Remove legacy plaintext fallback after migration period
        if (!encryptedString.contains(IV_SEPARATOR)) {
            return encryptedString // legacy plaintext — backward compat
        }
        val parts = encryptedString.split(IV_SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun getKeys(): List<String> {
        cachedKeys?.let { return it }
        val encryptedStr = prefs.getString(PREF_KEY_ARRAY, null) ?: return emptyList()
        val jsonStr = decrypt(encryptedStr) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (_: Exception) {
        }
        cachedKeys = list
        return list
    }

    @Synchronized
    private fun saveKeys(keys: List<String>): Boolean {
        val arr = JSONArray(keys)
        return try {
            prefs.edit().putString(PREF_KEY_ARRAY, encrypt(arr.toString())).apply()
            cachedKeys = keys
            true
        } catch (_: Exception) {
            cachedKeys = null
            false
        }
    }

    @Synchronized
    fun addKey(key: String): Boolean {
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

    fun getNextKey(): String? {
        val keys = getKeys()
        if (keys.isEmpty()) return null
        
        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (invalidKeys.contains(key)) return@filter false
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
        invalidKeys.add(key)
    }

    fun getShortestWaitTimeMs(): Long? {
        val keys = getKeys()
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val waits = keys.filter { !invalidKeys.contains(it) }
            .mapNotNull { key ->
                val limitTime = rateLimitedKeys[key] ?: return@mapNotNull null
                val remaining = limitTime - now
                if (remaining > 0) remaining else null
            }
        return waits.minOrNull()
    }
}
