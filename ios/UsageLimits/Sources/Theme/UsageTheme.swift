import SwiftUI
// `Severity` lives in the kit and is named throughout this file. Swift imports are per-file, so
// another file in the same target importing it buys this one nothing — which is how a file that
// looked fine failed to build in two targets at once.
import UsageLimitsKit

/// The palette, kept numerically identical to the Android one.
///
/// Same product, same colours: a user with the app on both a phone and an iPad should not see
/// two different shades of "running low". The hex values are copied from
/// `app/src/main/kotlin/com/usagelimits/ui/theme/Color.kt`, and any change belongs in both.
enum UsageColors {

    static let background = Color(hex: 0x0F0F0E)
    static let surface = Color(hex: 0x1A1918)
    static let surfaceElevated = Color(hex: 0x242322)
    static let outline = Color(hex: 0x3A3936)

    static let textPrimary = Color(hex: 0xF0EEE6)
    static let textSecondary = Color(hex: 0xB4B2A9)
    static let textTertiary = Color(hex: 0x8A887F)

    static let terracotta = Color(hex: 0xD97757)

    static let teal = Color(hex: 0x4FBFA8)
    static let green = Color(hex: 0x6FBF73)
    static let amber = Color(hex: 0xE0A33E)
    static let red = Color(hex: 0xD9584F)
    static let slate = Color(hex: 0x6B6960)

    /// Lifted variants for small text.
    ///
    /// Red and slate carry enough weight as a filled bar or a 7-point dot, but as label text on
    /// a tinted container they fall under the 4.5:1 contrast floor, so those two are lightened.
    static let redText = Color(hex: 0xE8756B)
    static let slateText = Color(hex: 0xA29F94)

    static let progressTrack = Color(hex: 0x2E2D2B)
}

/// Maps a severity onto the palette.
///
/// Kept in one place so screens, widgets and notifications colour a status identically — the
/// same reason the Android side has a single `SeverityPalette`.
enum SeverityPalette {

    static func accent(_ severity: Severity) -> Color {
        switch severity {
        case .healthy: return UsageColors.green
        case .medium, .low: return UsageColors.amber
        case .exhausted, .error: return UsageColors.red
        case .stale: return UsageColors.slate
        }
    }

    /// The tone to write a status *word* in — see `UsageColors.redText`.
    static func text(_ severity: Severity) -> Color {
        switch severity {
        case .exhausted, .error: return UsageColors.redText
        case .stale: return UsageColors.slateText
        default: return accent(severity)
        }
    }

    static func container(_ severity: Severity) -> Color {
        accent(severity).opacity(0.15)
    }

    /// Bar colour for one window.
    ///
    /// An untouched window gets teal rather than green, preserving the distinction between
    /// "nothing used yet" and "healthy but partly used".
    static func bar(remainingPercent: Double?, severity: Severity) -> Color {
        if let remaining = remainingPercent, remaining >= 99.5 { return UsageColors.teal }
        return accent(severity)
    }

    static func label(_ severity: Severity) -> String {
        switch severity {
        case .healthy: return "Healthy"
        case .medium: return "Moderate"
        case .low: return "Low"
        case .exhausted: return "Exhausted"
        case .stale: return "Stale"
        case .error: return "Error"
        }
    }
}

/// Corner radii. Large throughout — the reference design's most distinctive trait.
enum UsageRadius {
    static let large: CGFloat = 24
}

extension Color {
    /// `Color(hex: 0xD97757)`, so the palette above reads the same as the Kotlin one.
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1)
    }
}

/// A rounded panel on the app's dark ground.
struct UsageCard<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(UsageColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: UsageRadius.large, style: .continuous))
    }
}

/// The proportional bar every quota row is read from.
///
/// Drawn with a `GeometryReader` rather than `ProgressView` because the track, the fill colour
/// and the corner radius all have to match the Android rendering exactly, and the system style
/// gives up control of all three.
struct UsageBar: View {
    let remainingPercent: Double?
    let severity: Severity
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(UsageColors.progressTrack)
                Capsule()
                    .fill(SeverityPalette.bar(remainingPercent: remainingPercent, severity: severity))
                    // An unknown percentage draws nothing rather than a full or empty bar:
                    // either would state a fact the provider did not report.
                    .frame(width: geometry.size.width * fraction)
            }
        }
        .frame(height: height)
    }

    private var fraction: CGFloat {
        guard let remaining = remainingPercent else { return 0 }
        return CGFloat(min(max(remaining, 0), 100) / 100)
    }
}
