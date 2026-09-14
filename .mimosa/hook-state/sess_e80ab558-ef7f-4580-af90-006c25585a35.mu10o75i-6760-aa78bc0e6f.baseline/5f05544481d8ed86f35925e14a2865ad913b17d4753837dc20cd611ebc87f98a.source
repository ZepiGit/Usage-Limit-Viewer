package com.usagelimits.core.network

/** Failures the sync layer and UI can react to differently. */
sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Token rejected. The account needs re-authentication. */
    class Unauthorized(message: String = "Authentication expired") : ProviderException(message)

    /** Authenticated but not permitted — revoked access, or a plan without this quota. */
    class Forbidden(message: String = "Access denied") : ProviderException(message)

    /** Provider asked us to back off. [retryAfterMs] mirrors Retry-After when present. */
    class RateLimited(val retryAfterMs: Long?, message: String = "Rate limited") :
        ProviderException(message)

    /** Provider-side failure; retrying later is reasonable. */
    class ServerError(val statusCode: Int, message: String) : ProviderException(message)

    /** No usable network. */
    class Offline(message: String = "No network connection", cause: Throwable? = null) :
        ProviderException(message, cause)

    /**
     * The response parsed but did not contain what we expect — most likely the upstream
     * endpoint changed shape. Surfaced distinctly so it can be reported rather than
     * mistaken for an outage.
     */
    class MalformedPayload(message: String, cause: Throwable? = null) :
        ProviderException(message, cause)

    /** The user dismissed or denied the login. */
    class LoginCancelled(message: String = "Login cancelled") : ProviderException(message)

    class Unexpected(message: String, cause: Throwable? = null) : ProviderException(message, cause)
}
