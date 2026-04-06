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

    private val rateLimitedKeys = mutableMapOf<String, Long>()
    private val invalidKeys = mutableSetOf<String>()
    private val roundRobinIndex = AtomicInteger(0)
    private var cachedKeys: List<String>? = null

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
            e.printStackTrace()
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun encrypt(plainText: String): String {
        return try {
            val secretKey = getSecretKey() ?: return plainText // Fallback to plain text if keystore fails
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherTextString = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            "$ivString$IV_SEPARATOR$cipherTextString"
        } catch (e: Exception) {
            e.printStackTrace()
            plainText // Fallback
        }
    }

    private fun decrypt(encryptedString: String): String {
        return try {
            if (!encryptedString.contains(IV_SEPARATOR)) {
                return encryptedString // Assume it's plain text fallback or unencrypted legacy data
            }
            val parts = encryptedString.split(IV_SEPARATOR)
            if (parts.size != 2) return encryptedString
            
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey = getSecretKey() ?: return encryptedString
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "[]" // Fallback to empty array on decryption failure
        }
    }

    fun getKeys(): List<String> {
        cachedKeys?.let { return it }
        val encryptedStr = prefs.getString(PREF_KEY_ARRAY, null) ?: return emptyList()
        val jsonStr = decrypt(encryptedStr)
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedKeys = list
        return list
    }

    private fun saveKeys(keys: List<String>) {
        cachedKeys = null
        val arr = JSONArray(keys)
        val encryptedStr = encrypt(arr.toString())
        prefs.edit().putString(PREF_KEY_ARRAY, encryptedStr).apply()
    }

    fun addKey(key: String) {
        val keys = getKeys().toMutableList()
        if (!keys.contains(key)) {
            keys.add(key)
            saveKeys(keys)
        }
        invalidKeys.remove(key)
    }

    fun removeKey(key: String) {
        val keys = getKeys().toMutableList()
        keys.remove(key)
        saveKeys(keys)
        rateLimitedKeys.remove(key)
        invalidKeys.remove(key)
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
