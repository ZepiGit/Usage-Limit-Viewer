package com.usagelimits.core.network

/**
 * Every provider URL, client identifier and request header the app sends, in one place.
 *
 * These values are reverse-engineered from the first-party CLI/desktop clients by way of
 * CLIProxyAPI and its Management Center (see docs/provider-auth-research.md for the exact
 * source files and the commits they were read at). They are *not* a documented third-party
 * API and can change without notice, so they are centralised here: an upstream change should
 * be a one-file edit, never a hunt through parsers.
 *
 * Nothing here is a secret. The OAuth client IDs are the public identifiers the official
 * clients ship; see [Antigravity.CLIENT_SECRET] for the one value that needs a caveat.
 */
object ProviderEndpoints {

    /**
     * OpenAI Codex / ChatGPT subscription.
     *
     * Source: CLIProxyAPI internal/auth/codex/openai_auth.go, sdk/auth/codex_device.go, and
     * Management Center src/utils/quota/constants.ts.
     */
    object Codex {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

        /**
         * Authorization code + PKCE with a loopback redirect — the flow the Codex CLI itself
         * runs, and the default here. The client registration pins this exact redirect, so
         * the app binds this port (see LoopbackServer) just as it does for Claude and
         * Antigravity. The extra parameters are the ones the first-party client sends; the
         * authorize page shapes what it shows by them.
         */
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val REDIRECT_PORT = 1455
        const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT/auth/callback"
        const val AUTHORIZE_SCOPE = "openid profile email offline_access"
        val AUTHORIZE_EXTRA_PARAMS = mapOf(
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs",
        )

        // Device authorization — the fallback when the redirect port is taken. No redirect
        // URI has to be registered and no loopback server has to run. The provider generates
        // the PKCE pair and returns it with the code.
        const val DEVICE_USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
        const val DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
        const val DEVICE_VERIFICATION_URL = "https://auth.openai.com/codex/device"

        /** Redirect the device-issued code must be exchanged against. Never navigated to. */
        const val DEVICE_EXCHANGE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"

        const val TOKEN_URL = "https://auth.openai.com/oauth/token"

        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        const val RESET_CREDITS_URL =
            "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"
        const val RESET_CREDITS_CONSUME_URL =
            "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume"

        /**
         * Identifies the app as a Codex client. The backend varies its response by client, so
         * this is a compatibility marker rather than an attempt to look like something else.
         */
        const val USER_AGENT = "codex-tui/0.149.1 (Android; arm64) UsageLimits"

        /** Sent on the refresh grant only. Neither the device flow nor the code exchange uses it. */
        const val SCOPE = "openid email profile offline_access"

        /** Sent on the usage and reset-credit calls to select the account. */
        const val HEADER_ACCOUNT_ID = "Chatgpt-Account-Id"

        /**
         * Only the reset-credit endpoints require these; the usage endpoint does not send
         * them. Verified in Management Center codex/data.ts (fetchCodexResetCredits).
         */
        val RESET_CREDIT_HEADERS = mapOf(
            "OpenAI-Beta" to "codex-1",
            "Originator" to "Codex Desktop",
        )
    }

    /**
     * Claude / Anthropic subscription.
     *
     * Source: CLIProxyAPI internal/auth/claude/anthropic_auth.go and Management Center
     * src/utils/quota/constants.ts.
     */
    object Claude {
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"

        /** Both the code exchange and the refresh go here (platform.claude.com, not api.). */
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"

        const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

        /**
         * Loopback redirect, per RFC 8252 for native apps. The port is fixed because the
         * provider's client registration pins it, so the app must bind exactly this port.
         */
        const val REDIRECT_URI = "http://localhost:54545/callback"
        const val REDIRECT_PORT = 54545

        const val SCOPE =
            "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

        const val BETA_HEADER = "oauth-2025-04-20"

        /**
         * Usage window keys, in display order.
         *
         * `iguana_necktie` is upstream's current key for the Fable weekly window — a codename,
         * not a typo. The parser tolerates its absence and ignores unknown siblings, so a
         * rename upstream degrades to "one window missing" rather than a failed parse.
         */
        val USAGE_WINDOW_KEYS = listOf(
            "five_hour" to "5h limit",
            "seven_day" to "Weekly",
            "seven_day_oauth_apps" to "Weekly (OAuth apps)",
            "seven_day_opus" to "Weekly (Opus)",
            "seven_day_sonnet" to "Weekly (Sonnet)",
            "seven_day_cowork" to "Weekly (Cowork)",
            "iguana_necktie" to "Weekly (Fable)",
        )
    }

    /**
     * Google Antigravity.
     *
     * Source: CLIProxyAPI internal/auth/antigravity/constants.go and Management Center
     * src/utils/quota/constants.ts.
     */
    object Antigravity {
        const val CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"

        /**
         * Google "installed application" client secret.
         *
         * This is not a confidential credential: RFC 8252 §8.5 and Google's own installed-app
         * documentation treat it as public, and it ships in the desktop client already. It is
         * required because Google's token endpoint rejects the exchange without it for this
         * client type, and the Antigravity quota scopes are bound to this specific client —
         * a self-registered Android client cannot reach them.
         *
         * PKCE is still used, so possession of this string alone does not let an attacker
         * complete a flow. See docs/security.md for the full argument and the alternatives
         * that were rejected.
         */
        const val CLIENT_SECRET = "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"

        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"

        const val REDIRECT_PORT = 51121
        const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT/oauth-callback"

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        /**
         * Tried in order. The stable host is first because this call gates account creation:
         * CLIProxyAPI and the Quota Inspector both use `cloudcode-pa` for it, and only the
         * quota read needs the daily hosts' wider rollout.
         */
        val LOAD_CODE_ASSIST_URLS = listOf(
            "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
            "https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
        )

        /**
         * Tried in order until one answers. The daily/sandbox hosts are rolled out ahead of
         * the stable one, and which host serves a given account varies.
         */
        val QUOTA_URLS = listOf(
            "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
            "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary",
            "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
        )

        const val CLI_VERSION = "1.0.13"
        const val USER_AGENT = "antigravity/cli/$CLI_VERSION (aidev_client; os_type=linux; arch=arm64)"
    }

    /**
     * xAI / Grok.
     *
     * Source: CLIProxyAPI internal/auth/xai/{xai,types}.go and Management Center
     * src/utils/quota/constants.ts.
     */
    object Xai {
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val ISSUER = "https://auth.x.ai"

        /** Endpoints are discovered rather than hardcoded; see XaiProvider.discover(). */
        const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"

        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        const val BILLING_CREDITS_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        const val BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing"
        const val ME_URL = "https://api.x.ai/v1/me"

        const val CLIENT_VERSION = "0.2.91"
        const val USER_AGENT = "grok-pager/$CLIENT_VERSION grok-shell/$CLIENT_VERSION (android; aarch64)"

        val IDENTITY_HEADERS = mapOf(
            "x-xai-token-auth" to "xai-grok-cli",
            "x-grok-client-version" to CLIENT_VERSION,
        )
    }

    /**
     * Kimi Code.
     *
     * No OAuth here, deliberately. Kimi Code's device flow is bound to `kimi-cli`'s client id
     * and `api.kimi.com` gates on an `X-Msh-Platform` allowlist that answers anything else
     * with `403 access_terminated` — so driving it would mean impersonating another client
     * past an access control the provider put there on purpose. The user brings a key from
     * their own console instead. See docs/providers-kimi.md.
     */
    object Kimi {
        const val USAGE_ENDPOINT = "https://api.kimi.com/coding/v1/usages"
        const val CONSOLE_URL = "https://www.kimi.com/code"
    }
}
