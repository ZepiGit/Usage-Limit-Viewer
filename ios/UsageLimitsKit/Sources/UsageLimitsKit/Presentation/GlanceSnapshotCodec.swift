import Foundation

/// The file the app writes and the widget reads.
///
/// A widget extension is a separate process with no access to the app's database, so what it
/// renders is whatever the app last left in the shared container. That makes the encoding a
/// contract between two binaries, and it lives here — in the layer both of them link — for a
/// specific reason: the first version of it was a private wire format inside the widget, which
/// re-derived `overallSeverity` from the accounts it had decoded. It derived it differently
/// from `GlanceModel`, so the same data produced one verdict in the app and another on the home
/// screen. A second copy of a rule is a second answer to the same question.
///
/// So the snapshot is encoded whole and decoded whole. Nothing is recomputed on the way in.
public enum GlanceSnapshotCodec {

    /// The file name inside the shared container, so neither side hard-codes it twice.
    public static let fileName = "glance-snapshot.json"

    /// ISO-8601 on both sides. The default strategy encodes a `Date` as a floating-point count
    /// of seconds since 2001, which is correct but unreadable — and this file is the first thing
    /// anyone will look at when a widget shows the wrong time.
    private static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    public static func encode(_ snapshot: GlanceSnapshot) throws -> Data {
        try encoder().encode(snapshot)
    }

    /// Decodes, or reports the empty snapshot.
    ///
    /// A widget cannot open a dialogue or ask for a retry, so every failure — a missing file, a
    /// half-written one, a field this build does not understand — has the same available
    /// outcome. Empty renders as "stale", never as an all-clear, so an absence of data is not
    /// laundered into a reassuring tile.
    public static func decode(_ data: Data?) -> GlanceSnapshot {
        guard let data, !data.isEmpty else { return .empty }
        return (try? decoder().decode(GlanceSnapshot.self, from: data)) ?? .empty
    }

    /// Writes the snapshot into a container directory, atomically.
    ///
    /// Atomic because the widget can be reading while the app writes: a partial file decodes to
    /// nothing, and a tile that blanks every half hour is worse than one that lags.
    public static func write(_ snapshot: GlanceSnapshot, toDirectory directory: URL) throws {
        try encode(snapshot).write(
            to: directory.appendingPathComponent(fileName),
            options: ContainerFile.writingOptions)
    }

    public static func read(fromDirectory directory: URL) -> GlanceSnapshot {
        decode(try? Data(contentsOf: directory.appendingPathComponent(fileName)))
    }
}
