import Foundation

/// Lenient access into provider payloads.
///
/// Every provider here is an undocumented internal endpoint, so parsers read defensively:
/// unknown fields are ignored, absent fields yield nil rather than throwing, and both
/// snake_case and camelCase are accepted because upstream serves both depending on the
/// endpoint. A field that disappears should cost one row, not the whole screen.
///
/// Deliberately works over `[String: Any]` from `JSONSerialization` rather than `Codable`.
/// `Codable` wants a fixed shape declared up front; these payloads change without notice and
/// carry keys under several spellings, which is exactly the case `Codable` handles worst.
public enum JSONSupport {

    /// Epoch values above this are already milliseconds. Roughly the year 2286.
    private static let secondsUpperBound: Int64 = 10_000_000_000

    private static func first(_ source: [String: Any]?, _ names: [String]) -> Any? {
        guard let source else { return nil }
        for name in names {
            if let value = source[name], !(value is NSNull) { return value }
        }
        return nil
    }

    public static func object(_ source: [String: Any]?, _ names: String...) -> [String: Any]? {
        first(source, names) as? [String: Any]
    }

    public static func string(_ source: [String: Any]?, _ names: String...) -> String? {
        guard let value = first(source, names) else { return nil }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed == "null" ? nil : trimmed
        }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }

    /// Whether a decoded JSON value was a boolean literal, as opposed to a number.
    ///
    /// On Darwin `JSONSerialization` hands `true` back as an `NSNumber` (`__NSCFBoolean`), so
    /// `as? NSNumber` and even `as? Bool` on a `1` both succeed and the two types are
    /// indistinguishable by casting. The Kotlin twin reads a boolean as no number at all, and
    /// an `NSNumber` of 1 as no boolean; without this check Swift read `"percent": true` as
    /// 1 % used and `"has_claude_max": 1` as Max — same payload, different answer.
    private static func isBooleanLiteral(_ value: Any) -> Bool {
        #if canImport(Darwin)
        return CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
        #else
        // swift-corelibs-foundation: `value is Bool` is TRUE for every NSNumber — a probe
        // showed `1` and `0` bridging to Bool exactly as `true` and `false` do — so a cast
        // cannot tell them apart and would turn every number into "a boolean". What does
        // differ is the dynamic class: a JSON literal decodes to `__NSCFBoolean`, a number
        // to `NSNumber`.
        return value is NSNumber && String(describing: type(of: value)) == "__NSCFBoolean"
        #endif
    }

    /// Accepts a JSON number or a numeric string — providers mix the two for the same field.
    public static func double(_ source: [String: Any]?, _ names: String...) -> Double? {
        guard let value = first(source, names) else { return nil }
        if isBooleanLiteral(value) { return nil }
        if let number = value as? NSNumber {
            let result = number.doubleValue
            return result.isFinite ? result : nil
        }
        if let text = value as? String {
            guard let result = Double(text.trimmingCharacters(in: .whitespacesAndNewlines)),
                  result.isFinite else { return nil }
            return result
        }
        return nil
    }

    public static func int64(_ source: [String: Any]?, _ names: String...) -> Int64? {
        guard let value = first(source, names) else { return nil }
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let exact = Int64(trimmed) { return exact }
            if let approx = Double(trimmed), approx.isFinite { return Int64(approx) }
        }
        return nil
    }

    public static func bool(_ source: [String: Any]?, _ names: String...) -> Bool? {
        guard let value = first(source, names) else { return nil }
        // A boolean literal, or the words "true"/"false" — never a number. See `isBooleanLiteral`.
        if isBooleanLiteral(value), let flag = value as? Bool { return flag }
        if let text = value as? String {
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        }
        return nil
    }

    public static func array(_ source: [String: Any]?, _ names: String...) -> [Any] {
        first(source, names) as? [Any] ?? []
    }

    // ISO8601DateFormatter is not cheap to build and these run per window per sync, so the two
    // variants are made once. Both are needed: the withFractionalSeconds option makes the
    // parser reject a timestamp *without* fractional seconds rather than tolerate it.
    private static let iso8601Fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let iso8601Plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    /// Parses the assorted timestamp shapes providers emit into a `Date`.
    ///
    /// Between the four providers this sees ISO-8601 with and without fractional seconds,
    /// epoch seconds, and epoch millis — sometimes for the same concept on different endpoints.
    public static func date(_ value: String?) -> Date? {
        guard let raw = value?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        if let epoch = Int64(raw) { return date(epoch: epoch) }
        if let epoch = Double(raw), epoch.isFinite { return date(epoch: Int64(epoch)) }
        // Sub-millisecond precision is trimmed to three digits before parsing, as the Kotlin
        // twin does: providers overrun it (xAI sends six), and the formatter is stricter than
        // java.time about what it will take.
        let normalised = raw.replacingOccurrences(
            of: #"(\.\d{3})\d+"#, with: "$1", options: .regularExpression)
        if let parsed = iso8601Fractional.date(from: normalised) { return parsed }
        if let parsed = iso8601Plain.date(from: normalised) { return parsed }
        // A bare local date-time with no offset: assume UTC, matching the Android parser —
        // through BOTH formatters, because a bare "…T00:00:00.5" carries a fraction the plain
        // one rejects, and Android parsed it.
        return iso8601Fractional.date(from: normalised + "Z")
            ?? iso8601Plain.date(from: normalised + "Z")
    }

    /// Disambiguates epoch seconds from epoch millis by magnitude.
    public static func date(epoch value: Int64?) -> Date? {
        guard let value, value > 0 else { return nil }
        let seconds = value < secondsUpperBound ? Double(value) : Double(value) / 1000.0
        return Date(timeIntervalSince1970: seconds)
    }
}
