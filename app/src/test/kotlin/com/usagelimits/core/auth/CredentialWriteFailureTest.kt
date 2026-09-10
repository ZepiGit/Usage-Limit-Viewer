package com.usagelimits.core.auth

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What the credential store does when the disk says no.
 *
 * These paths decide whether an account survives, and none of them could be run before:
 * `AndroidKeyStore` is not a provider a JVM test has, so the store was reachable only on a
 * device. The cipher is now injectable — for this reason and no other — while the default
 * stays the real Keystore one, so nothing here can accidentally certify a weaker cipher than
 * production uses.
 *
 * The stake is specific. Every provider this app talks to rotates the refresh token when it is
 * used, so the moment a refresh succeeds the token on disk is dead. A write that fails without
 * saying so leaves the dead one there and the live one in memory only: the sync that just ran
 * still works, and the next launch has an account that can never authenticate again.
 */
class CredentialWriteFailureTest {

    /** Encrypts by doing nothing, so the test exercises the store rather than the cipher. */
    private object PlainCrypto : KeystoreCredentialStore.Crypto {
        override suspend fun encrypt(plaintext: String) = plaintext
        override suspend fun decrypt(payload: String) = payload
    }

    /** Preferences whose commit can be told to fail, which is the whole point. */
    private class FailableEditor(
        private val backing: MutableMap<String, String?>,
        private val succeed: () -> Boolean,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { removals += backing.keys }

        override fun commit(): Boolean {
            if (!succeed()) return false
            removals.forEach { backing.remove(it) }
            backing.putAll(pending)
            return true
        }

        override fun apply() { commit() }

        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }

    private class FailablePrefs(var succeed: Boolean = true) : SharedPreferences {
        val backing = mutableMapOf<String, String?>()

        override fun edit(): SharedPreferences.Editor = FailableEditor(backing) { succeed }
        override fun getString(key: String, defValue: String?) = backing[key] ?: defValue
        override fun getAll(): MutableMap<String, *> = backing
        override fun contains(key: String) = backing.containsKey(key)

        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    // No Robolectric and no Context: with the preferences supplied, this store needs neither.
    // That is not only faster — a fourth Robolectric class in this JVM tripped its native
    // runtime loader racing to open the font archive, a failure with nothing to do with the
    // code under test.
    private fun store(prefs: FailablePrefs) =
        KeystoreCredentialStore(prefs = prefs, crypto = PlainCrypto)

    private val credentials = OAuthCredentials(
        accessToken = "access-value",
        refreshToken = "refresh-value",
        idToken = null,
        expiresAt = 1_700_000_000_000L,
    )

    @Test
    fun `a save that does not reach disk is reported, not swallowed`() {
        val prefs = FailablePrefs(succeed = false)

        assertThrows(CredentialWriteException::class.java) {
            runBlocking { store(prefs).save("codex_a", credentials) }
        }
        // Nothing was persisted, so this is not a partial write being over-reported.
        assertNull(prefs.backing["cred_codex_a"])
    }

    @Test
    fun `a delete that does not reach disk is reported, not swallowed`() = runBlocking {
        val prefs = FailablePrefs()
        val store = store(prefs)
        store.save("codex_a", credentials)

        prefs.succeed = false
        assertThrows(CredentialWriteException::class.java) {
            runBlocking { store.delete("codex_a") }
        }
        // The credential the user asked to remove is still there — which is exactly why the
        // caller has to be told rather than left believing it is gone.
        assertEquals(setOf("codex_a"), store.references())
    }

    @Test
    fun `a successful round trip still works`() = runBlocking {
        val prefs = FailablePrefs()
        val store = store(prefs)

        store.save("codex_a", credentials)
        val loaded = store.load("codex_a")

        assertEquals("access-value", loaded?.accessToken)
        assertEquals("refresh-value", loaded?.refreshToken)
        assertEquals(setOf("codex_a"), store.references())
    }

    @Test
    fun `an unreadable record reads as absent rather than crashing`() = runBlocking {
        val prefs = FailablePrefs()
        prefs.backing["cred_codex_a"] = "not valid json at all"

        // Null, because the caller's remedy — reconnect this account — is the same as for a
        // record that was never there. The difference is recorded in the log, which is the
        // only place a wholesale key loss would otherwise show up.
        assertNull(store(prefs).load("codex_a"))
        // The reference still lists, so the account is not silently forgotten either.
        assertEquals(setOf("codex_a"), store(prefs).references())
    }
}
