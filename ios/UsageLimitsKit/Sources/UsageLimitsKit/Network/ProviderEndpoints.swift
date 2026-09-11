import Foundation

/// Every provider URL, client identifier and request header the app sends, in one place.
///
/// Ported from the Android `ProviderEndpoints.kt`, value for value. These are reverse-engineered
/// from the first-party CLI and desktop clients by way of CLIProxyAPI and its Management Center
/// (see `docs/provider-auth-research.md` for the exact source files and commits). They are
/// **not** a documented third-party API and can change without notice, which is why they are
/// centralised: an upstream change should be a one-file edit on each platform, never a hunt
/// through parsers.
///
/// Nothing here is a secret. The OAuth client IDs are the public identifiers the official
/// clients ship; see `Antigravity.clientSecret` for the one value that needs a caveat.
public enum ProviderEndpoints {

    /// OpenAI Codex / ChatGPT subscription.
    public enum Codex {
        public static let clientID = "app_EMoamEEZ73f0CkXaXp7hrann"

        /// Authorization code + PKCE with a loopback redirect — the flow the Codex CLI itself
        /// runs, and the default on both platforms. The client registration pins this exact
        /// redirect, so the app listens on this port just as it does for Claude and
        /// Antigravity. The extra parameters are the ones the first-party client sends; the
        /// authorize page shapes what it shows by them.
        public static let authorizeURL = "https://auth.openai.com/oauth/authorize"
        public static let redirectURI = "http://localhost:1455/auth/callback"
        public static let authorizeScope = "openid profile email offline_access"
        public static let authorizeExtraParameters = [
            "id_token_add_organizations": "true",
            "codex_cli_simplified_flow": "true",
            "originator": "codex_cli_rs",
        ]

        // Device authorization — the fallback when the redirect port is taken. No redirect URI
        // has to be registered and no loopback listener has to run. The provider generates the
        // PKCE pair and returns it with the code, which means PKCE here is not the
        // client-binding guarantee it normally is; that caveat is recorded in the research doc.
        public static let deviceUserCodeURL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
        public static let deviceTokenURL = "https://auth.openai.com/api/accounts/deviceauth/token"
        public static let deviceVerificationURL = "https://auth.openai.com/codex/device"

        /// Redirect the device-issued code must be exchanged against. Never navigated to.
        public static let deviceExchangeRedirectURI = "https://auth.openai.com/deviceauth/callback"

        public static let tokenURL = "https://auth.openai.com/oauth/token"

        public static let usageURL = "https://chatgpt.com/backend-api/wham/usage"
        public static let resetCreditsURL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"
        public static let resetCreditsConsumeURL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume"

        /// Identifies the app as a Codex client. The backend varies its response by client, so
        /// this is a compatibility marker rather than an attempt to look like something else.
        public static let userAgent = "codex-tui/0.149.1 (iOS; arm64) UsageLimits"

        /// Sent on the refresh grant only. Neither the device flow nor the code exchange uses it.
        public static let refreshScope = "openid profile email"

        /// Selects the account on the usage and reset-credit calls. Omitted entirely when the
        /// real `chatgpt_account_id` is unknown — a JWT `sub` is a user id, not an account id,
        /// and sending it scopes the response to the wrong account.
        public static let accountIDHeader = "Chatgpt-Account-Id"

        /// Only the reset-credit endpoints take these; the usage endpoint does not.
        public static let resetCreditHeaders = [
            "OpenAI-Beta": "codex-1",
            "Originator": "Codex Desktop",
        ]
    }

    /// Claude / Anthropic subscription.
    public enum Claude {
        public static let clientID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        public static let authorizeURL = "https://claude.ai/oauth/authorize"

        /// Both the code exchange and the refresh go here — `platform.claude.com`, not `api.`.
        public static let tokenURL = "https://platform.claude.com/v1/oauth/token"

        public static let profileURL = "https://api.anthropic.com/api/oauth/profile"
        public static let usageURL = "https://api.anthropic.com/api/oauth/usage"

        /// On iOS this is a custom scheme rather than the loopback URI Android must use:
        /// `ASWebAuthenticationSession` handles the callback in-process, which removes the
        /// pinned-port design and every failure that came with it.
        ///
        /// It must still be registered with the provider to be accepted; until it is, the
        /// loopback URI remains the only value that works, and that is a real open question
        /// for the iOS port rather than a settled detail.
        public static let redirectURI = "http://localhost:54545/callback"
        public static let callbackScheme = "usagelimits"

        public static let scope =
            "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

        public static let betaHeader = "oauth-2025-04-20"

        /// Usage window keys, in display order.
        ///
        /// `iguana_necktie` is upstream's current key for the Fable weekly window — a codename,
        /// not a typo.
        public static let usageWindowKeys: [(key: String, label: String)] = [
            ("five_hour", "5h limit"),
            ("seven_day", "Weekly"),
            ("seven_day_oauth_apps", "Weekly (OAuth apps)"),
            ("seven_day_opus", "Weekly (Opus)"),
            ("seven_day_sonnet", "Weekly (Sonnet)"),
            ("seven_day_cowork", "Weekly (Cowork)"),
            ("iguana_necktie", "Weekly (Fable)"),
        ]
    }

    /// Google Antigravity.
    public enum Antigravity {
        public static let clientID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"

        /// Google "installed application" client secret.
        ///
        /// Not a confidential credential: RFC 8252 §8.5 and Google's own installed-app
        /// documentation treat it as public, and it ships in the desktop client already. It is
        /// required because Google's token endpoint rejects the exchange without it for this
        /// client type, and the Antigravity quota scopes are bound to this specific client — a
        /// self-registered client cannot reach them. PKCE is what actually protects the flow.
        /// See `docs/security.md` for the full argument and the rejected alternatives.
        public static let clientSecret = "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"

        public static let authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
        public static let tokenEndpoint = "https://oauth2.googleapis.com/token"
        public static let userInfoEndpoint = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"

        public static let redirectURI = "http://localhost:51121/oauth-callback"
        public static let callbackScheme = "usagelimits"

        public static let scopes = [
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        ]

        /// Tried in order. The stable host is first because this call gates account creation.
        public static let loadCodeAssistURLs = [
            "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
            "https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
        ]

        /// Tried in order until one answers with data. The daily and sandbox hosts are rolled
        /// out ahead of the stable one, and which host serves a given account varies.
        public static let quotaURLs = [
            "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
            "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary",
            "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
        ]

        public static let cliVersion = "1.0.13"
        public static let userAgent = "antigravity/cli/\(cliVersion) (aidev_client; os_type=darwin; arch=arm64)"

        /// `ideType` selects which product's onboarding record Cloud Code returns.
        /// `IDE_UNSPECIFIED` is the *Gemini Code Assist* value and yields a project carrying
        /// none of the Antigravity quota groups.
        public static let loadCodeAssistMetadata: [String: String] = [
            "ideType": "ANTIGRAVITY",
            "platform": "PLATFORM_UNSPECIFIED",
            "pluginType": "GEMINI",
        ]
    }

    /// xAI / Grok.
    public enum Xai {
        public static let clientID = "b1a00492-073a-47ea-816f-4c329264a828"
        public static let issuer = "https://auth.x.ai"

        /// Endpoints are discovered rather than hardcoded, then validated: HTTPS only, and the
        /// host must be exactly `x.ai` or a dot-anchored `.x.ai` subdomain. The anchor is what
        /// stops `notx.ai` and `x.ai.example.com` passing.
        public static let discoveryURL = "\(issuer)/.well-known/openid-configuration"
        public static let issuerHost = "x.ai"

        public static let scope = "openid profile email offline_access grok-cli:access api:access"
        public static let deviceCodeGrantType = "urn:ietf:params:oauth:grant-type:device_code"

        public static let billingCreditsURL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        public static let billingURL = "https://cli-chat-proxy.grok.com/v1/billing"
        public static let meURL = "https://api.x.ai/v1/me"

        public static let clientVersion = "0.2.91"
        public static let userAgent = "grok-pager/\(clientVersion) grok-shell/\(clientVersion) (ios; aarch64)"

        public static let identityHeaders = [
            "x-xai-token-auth": "xai-grok-cli",
            "x-grok-client-version": clientVersion,
        ]
    }

    /// Kimi Code.
    ///
    /// No OAuth here, deliberately — see docs/providers-kimi.md. The user brings a key from
    /// their own console because the device flow's client id belongs to `kimi-cli` and the
    /// model API gates on an `X-Msh-Platform` allowlist.
    public enum Kimi {
        public static let usageEndpoint = "https://api.kimi.com/coding/v1/usages"
        public static let consoleURL = "https://www.kimi.com/code"
    }
}
