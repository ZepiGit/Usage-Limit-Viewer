package com.usagelimits.providers.kimi

import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which account a pasted key belongs to.
 *
 * The behaviour only shows up on the SECOND sign-in, with a rotated key, which is why it needs
 * a test rather than a reading of the code: a digest changes when the key does, so naming the
 * account after one files a second account for the same subscription and abandons the first
 * holding a key that no longer works.
 *
 * Every key here is synthetic.
 */
class KimiIdentityTest {

    // A real client that is never called: `identity` reads a payload it is handed and touches
    // no network at all, which is exactly why it is worth testing on its own.
    private val provider = KimiProvider(HttpClient())

    private fun payload(text: String) = JsonSupport.parseObject(text)

    @Test
    fun `the id Kimi states wins over any digest of the key`() {
        val id = provider.identity(payload("""{ "user_id": "kimi-user-7" }"""), "sk-synthetic-1")

        assertEquals("kimi-user-7", id)
    }

    @Test
    fun `a rotated key lands on the same account`() {
        val first = provider.identity(payload("""{ "userId": "kimi-user-7" }"""), "sk-synthetic-1")
        val second = provider.identity(payload("""{ "userId": "kimi-user-7" }"""), "sk-synthetic-2")

        assertEquals(first, second)
    }

    @Test
    fun `a nested user object is read too`() {
        val id = provider.identity(payload("""{ "user": { "id": "kimi-user-9" } }"""), "sk-x")

        assertEquals("kimi-user-9", id)
    }

    @Test
    fun `a blank id is treated as absent rather than as an account named nothing`() {
        val id = provider.identity(payload("""{ "user_id": "   " }"""), "sk-synthetic-1")

        assertEquals(provider.identity(payload("{}"), "sk-synthetic-1"), id)
    }

    @Test
    fun `without an id the account is named by a digest that is not the key`() {
        val id = provider.identity(payload("{}"), "sk-synthetic-1")

        assertFalse("the key itself must never become the account id", id.contains("sk-synthetic-1"))
        assertNotEquals(id, provider.identity(payload("{}"), "sk-synthetic-2"))
    }
}
