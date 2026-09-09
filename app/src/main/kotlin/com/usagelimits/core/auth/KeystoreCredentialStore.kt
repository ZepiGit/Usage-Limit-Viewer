package com.usagelimits.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential store backed by an AES-GCM key held in the Android Keystore.
 *
 * The key never leaves the Keystore (it is not exportable, and on devices with a secure
 * element it is hardware-bound), so the ciphertext on disk is useless if the file is copied
 * off the device. Only the ciphertext lands in SharedPreferences; the plaintext exists just
 * long enough to build an [OAuthCredentials].
 *
 * `EncryptedSharedPreferences` would cover the same ground, but it is deprecated in
 * androidx.security 1.1.x and would still need this class's per-record shape, so the two
 * primitives it wraps (Keystore key + AEAD) are used directly instead.
 */
class KeystoreCredentialStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Serialises key creation so concurrent syncs cannot race the first-use generation. */
    private val keyMutex = Mutex()

    @Serializable
    private data class StoredCredentials(
        val accessToken: String,
        val refreshToken: String? = null,
        val idToken: String? = null,
        val expiresAt: Long? = null,
        val tokenEndpoint: String? = null,
    )

    override suspend fun load(reference: String): OAuthCredentials? = withContext(Dispatchers.IO) {
        val payload = prefs.getString(entryKey(reference), null) ?: return@withContext null
        runCatching {
            val decrypted = decrypt(payload)
            val stored = json.decodeFromString(StoredCredentials.serializer(), decrypted)
            OAuthCredentials(
                accessToken = stored.accessToken,
                refreshToken = stored.refreshToken,
                idToken = stored.idToken,
                expiresAt = stored.expiresAt,
                tokenEndpoint = stored.tokenEndpoint,
            )
        }.getOrNull()
    }

    override suspend fun save(reference: String, credentials: OAuthCredentials) {
        withContext(Dispatchers.IO) {
            val stored = StoredCredentials(
                accessToken = credentials.accessToken,
                refreshToken = credentials.refreshToken,
                idToken = credentials.idToken,
                expiresAt = credentials.expiresAt,
                tokenEndpoint = credentials.tokenEndpoint,
            )
            val plaintext = json.encodeToString(StoredCredentials.serializer(), stored)
            prefs.edit().putString(entryKey(reference), encrypt(plaintext)).commit()
        }
    }

    override suspend fun delete(reference: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(entryKey(reference)).commit()
        }
    }

    override suspend fun references(): Set<String> = withContext(Dispatchers.IO) {
        prefs.all.keys
            .filter { it.startsWith(ENTRY_PREFIX) }
            .map { it.removePrefix(ENTRY_PREFIX) }
            .toSet()
    }

    private suspend fun secretKey(): SecretKey = keyMutex.withLock {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Background sync must be able to refresh tokens while the device is locked,
                // so the key is not gated on user authentication.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private suspend fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // The GCM IV is generated per encryption and prefixed; it is not secret.
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + IV_SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private suspend fun decrypt(payload: String): String {
        val parts = payload.split(IV_SEPARATOR)
        require(parts.size == 2) { "malformed credential record" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun entryKey(reference: String) = ENTRY_PREFIX + reference

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "usage_limits_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "usage_limits_credentials"
        const val ENTRY_PREFIX = "cred_"
        const val IV_SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
    }
}
