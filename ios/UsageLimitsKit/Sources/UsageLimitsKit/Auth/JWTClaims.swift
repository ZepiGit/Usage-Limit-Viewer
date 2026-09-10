import Foundation

/// Reads the claims out of an OpenID Connect ID token.
///
/// **The signature is not verified, and this must never be used to make a trust decision.**
///
/// That is defensible here and only here: the token arrived over TLS, from the provider's own
/// token endpoint, in direct response to a request this app made moments earlier. There is no
/// point in the chain at which an attacker could have substituted one without already holding
/// the TLS session — and if they did, verifying a signature against a key fetched over the same
/// compromised channel would prove nothing. The claims are used to label an account in a list,
/// nothing more; the access token is what actually authorises anything.
///
/// An ID token from any other source — a deep link, a pasted string, another app — is outside
/// what this justifies, and must not be read with this.
public enum JWTClaims {

    /// The payload segment's claims, or nil if the string is not a readable JWT.
    ///
    /// Nil rather than throwing, because every caller's answer to "this token has no readable
    /// claims" is the same: fall back to asking the provider's profile endpoint.
    public static func decode(_ token: String?) -> [String: Any]? {
        guard let token else { return nil }
        let segments = token.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3, let data = base64URLDecode(String(segments[1])) else {
            return nil
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    public static func string(_ claims: [String: Any]?, _ names: String...) -> String? {
        for name in names {
            if let value = claims?[name] as? String, !value.isEmpty { return value }
        }
        return nil
    }

    /// OpenAI packs its account facts into a namespaced claim rather than top-level ones.
    public static func openAIAuth(_ claims: [String: Any]?) -> [String: Any]? {
        claims?["https://api.openai.com/auth"] as? [String: Any]
    }

    /// base64url, per RFC 7515 §2: `-` and `_` for `+` and `/`, and the padding omitted.
    ///
    /// Foundation's decoder rejects an unpadded string outright, so the padding has to be put
    /// back — a JWT payload is almost never a multiple of four characters, which makes this the
    /// difference between reading every token and reading none.
    private static func base64URLDecode(_ value: String) -> Data? {
        var normalised = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalised.count % 4
        if remainder > 0 {
            normalised.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: normalised)
    }
}

/// Who an account belongs to, as the provider describes it.
///
/// `externalAccountID` is the only required field: it is what makes the account identifiable
/// across sign-ins, and an account that cannot be identified cannot be de-duplicated or
/// re-authenticated. Everything else is decoration for the list.
public struct ProviderProfile: Sendable, Equatable {
    public let externalAccountID: String
    public let email: String?
    public let displayName: String?
    public let plan: String?

    /// Provider-specific facts a later fetch needs — the ChatGPT account id, the Google project.
    /// Kept as strings so the account type stays free of provider knowledge.
    public let attributes: [String: String]

    public init(
        externalAccountID: String,
        email: String? = nil,
        displayName: String? = nil,
        plan: String? = nil,
        attributes: [String: String] = [:]
    ) {
        self.externalAccountID = externalAccountID
        self.email = email
        self.displayName = displayName
        self.plan = plan
        self.attributes = attributes
    }
}
