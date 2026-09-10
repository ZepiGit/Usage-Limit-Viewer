import Foundation

/// How every file in the shared container is written and read.
///
/// One place, because the three files here — the account cache, the settings, the notification
/// ledger — plus the widget snapshot all have the same two requirements, and getting either
/// wrong on one of them is invisible until it matters.
enum ContainerFile {

    /// Atomic, and readable once the device has been unlocked at all.
    ///
    /// The protection class is stated rather than inherited. A file left at the stricter default
    /// cannot be opened while the device is locked, and the two readers that matter here run in
    /// exactly that state: a widget timeline refresh after an overnight reboot, and a background
    /// app refresh on a phone in a pocket. The symptom would be a permanently stale tile with
    /// nothing anywhere recording why — `Data(contentsOf:)` simply returns nil.
    ///
    /// `UntilFirstUserAuthentication` rather than `.none` is the right level because it is the
    /// weakest class that satisfies that, and none of these files holds token material — the
    /// tokens are in the keychain, under a matching `AfterFirstUnlockThisDeviceOnly`.
    ///
    /// Atomic here means a temp file in the same directory followed by a rename, so a reader
    /// sees the old file or the new one and never half of either. It is deliberately not
    /// fsynced: the cost of a sync on every write is real, and the worst case it prevents —
    /// power loss between the rename and the flush — leaves a file both readers already treat
    /// as absent, which costs one refresh.
    static var writingOptions: Data.WritingOptions {
        #if canImport(Darwin)
        return [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        #else
        // Linux has no data protection; the tests that exercise these paths run there.
        return [.atomic]
        #endif
    }
}
