package com.usagelimits.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 * A credential write that did not reach disk.
 *
 * Its own type rather than a generic failure, because the caller's stake is specific: the
 * refresh token these providers rotate on use is now live only in memory.
 */
class CredentialWriteException(reference: String) : IllegalStateException(
    "credential for $reference could not be written to disk",
)

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
 * primitives it wraps (Keystore key + AEAD) are used directly instead — and that library is
 * therefore not a dependency of this module at all.
 */
class KeystoreCredentialStore(
    /**
     * Where the ciphertext lands.
     *
     * Taken rather than derived from a `Context`, so a unit test can supply preferences whose
     * `commit` reports failure. That branch decides whether a rotated refresh token survives a
     * restart and is not reachable any other way off a device. Production uses the `Context`
     * constructor below and is unchanged.
     */
    private val prefs: SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * The AEAD pair, defaulting to the Keystore-backed one.
     *
     * Injectable for one reason: `AndroidKeyStore` is not a provider a JVM test has, so
     * without this the surrounding logic — what a failed write does, what an unreadable record
     * does — could only be argued rather than run. Null means the real one, so a test cannot
     * accidentally certify a weaker cipher than production uses.
     */
    private val crypto: Crypto? = null,
) : CredentialStore {

    /** What the app builds: the shared preferences file this store owns. */
    constructor(
        context: Context,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        json,
    )

    /** The two operations this store needs from the Keystore, named so a test can stand in. */
    interface Crypto {
        suspend fun encrypt(plaintext: String): String
        suspend fun decrypt(payload: String): String
    }


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
            val decrypted = crypto?.decrypt(payload) ?: decrypt(payload)
            val stored = json.decodeFromString(StoredCredentials.serializer(), decrypted)
            OAuthCredentials(
                accessToken = stored.accessToken,
                refreshToken = stored.refreshToken,
                idToken = stored.idToken,
                expiresAt = stored.expiresAt,
                tokenEndpoint = stored.tokenEndpoint,
            )
        }.onFailure { error ->
            // A record that EXISTS but cannot be read still returns null, because the caller's
            // response is the same either way: tell the user to reconnect, which is the correct
            // remedy for a credential the app can no longer decrypt. But the two are not the
            // same event, and only one of them means something went wrong — a Keystore key
            // replaced under the same alias makes EVERY account fail at once, and without this
            // line nothing anywhere would say so.
            //
            // The exception, not the payload: the payload is the ciphertext and the message of
            // a deserialisation failure can quote its input.
            Log.w(TAG, "stored credential could not be read: ${error.javaClass.simpleName}")
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
            // `commit` reports whether the write reached disk, and dropping that answer is how
            // an account is lost for good: these providers rotate the refresh token on use, so
            // a save that silently fails leaves the spent token on disk and the live one only
            // in memory. The sync that just refreshed still works; the next launch has nothing
            // that works, and no record of why. Throwing makes it one account's failed sync,
            // which the card already knows how to show.
            val written = prefs.edit()
                .putString(entryKey(reference), crypto?.encrypt(plaintext) ?: encrypt(plaintext))
                .commit()
            if (!written) throw CredentialWriteException(reference)
        }
    }

    override suspend fun delete(reference: String) {
        withContext(Dispatchers.IO) {
            // Same reasoning inverted: a delete reported as done while the ciphertext is still
            // on disk means a credential the user asked to remove comes back on restart.
            val removed = prefs.edit().remove(entryKey(reference)).commit()
            if (!removed) throw CredentialWriteException(reference)
        }
    }

    override suspend fun references(): Set<String> = withContext(Dispatchers.IO) {
        prefs.all.keys
            .filter { it.startsWith(ENTRY_PREFIX) }
            .map { it.removePrefix(ENTRY_PREFIX) }
            .toSet()
    }

    private suspend fun secretKey(): SecretKey = KEY_MUTEX.withLock {
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
        const val TAG = "CredentialStore"

        /**
         * Guards key creation for the whole process, not for one instance.
         *
         * The alias is a single global name, so two stores that both find it missing both
         * generate — and the second replaces the first's key. Anything the first already
         * encrypted is then undecryptable for good. An instance field cannot exclude that,
         * because the two racers are different instances; only a shared lock can.
         */
        val KEY_MUTEX = Mutex()

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "usage_limits_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "usage_limits_credentials"
        const val ENTRY_PREFIX = "cred_"
        const val IV_SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
    }
}
