package com.usagelimits.core.notifications

import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.sync.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The producer and the matcher have to agree, and nothing was making them.
 *
 * The evaluator asked whether the stored error message contained "expired". That happened to be
 * true here and was false on iOS, where the same layer emitted "This account needs signing in
 * again." — so the reconnect notification was DEAD on that platform: no failure, no warning,
 * just an account that quietly stopped updating and a user who was never told why.
 *
 * Android was one reword away from the same silence. These tests are what now stands between
 * the two ends.
 */
class SignInExpiredMessageTest {

    @Test
    fun `the message a rejected credential produces is the one the evaluator looks for`() {
        val produced = ProviderException.Unauthorized("synthetic").userMessage()

        assertTrue(
            "the evaluator must recognise what the sync layer actually stores: $produced",
            NotificationEvaluator.meansSignInExpired(produced),
        )
        assertEquals(NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE, produced)
    }

    @Test
    fun `a snapshot cached by an earlier build is still recognised`() {
        // The cache outlives the upgrade, so an account holding the old wording must not go
        // quiet until the next successful sync replaces it.
        assertTrue(NotificationEvaluator.meansSignInExpired("Sign-in expired — reconnect this account"))
        assertTrue(NotificationEvaluator.meansSignInExpired("This account needs signing in again."))
    }

    @Test
    fun `an unrelated failure is not reported as an expired sign-in`() {
        assertFalse(NotificationEvaluator.meansSignInExpired("No usage could be read (timeout)."))
        assertFalse(NotificationEvaluator.meansSignInExpired(null))
    }
}
