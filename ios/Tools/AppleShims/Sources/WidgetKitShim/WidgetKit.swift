@_exported import AppleShims
import Foundation

public protocol TimelineEntry {
    var date: Date { get }
}

public enum TimelineReloadPolicy {
    case atEnd
    case never
    case after(Date)
}

public struct Timeline<Entry: TimelineEntry> {
    public init(entries: [Entry], policy: TimelineReloadPolicy) {}
}

public struct TimelineProviderContext {
    public var family: WidgetFamily { .systemSmall }
}

public protocol TimelineProvider {
    associatedtype Entry: TimelineEntry
    /// Real WidgetKit vends this nested name; conformers spell their parameters `in
    /// context: Context`, so without it every method signature fails to resolve.
    typealias Context = TimelineProviderContext
    func placeholder(in context: TimelineProviderContext) -> Entry
    func getSnapshot(in context: TimelineProviderContext, completion: @escaping (Entry) -> Void)
    func getTimeline(in context: TimelineProviderContext,
                     completion: @escaping (Timeline<Entry>) -> Void)
}

/// The app spells this `WidgetFamily`; the cases live in the core module so
/// `EnvironmentValues` can name them without depending on this one.
public typealias WidgetFamily = WidgetFamilyPlaceholder

public protocol WidgetConfiguration {}

public struct StaticConfiguration<Provider: TimelineProvider>: WidgetConfiguration {
    public init(kind: String, provider: Provider,
                content: @escaping (Provider.Entry) -> any View) {}
    public func configurationDisplayName(_ name: String) -> Self { self }
    public func description(_ text: String) -> Self { self }
    public func supportedFamilies(_ families: [WidgetFamily]) -> Self { self }
    public func contentMarginsDisabled() -> Self { self }
}

public protocol Widget {
    associatedtype Body: WidgetConfiguration
    init()
    var body: Body { get }
}

@resultBuilder
public enum WidgetBundleBuilder {
    public static func buildBlock<W: Widget>(_ widget: W) -> W { widget }
    public static func buildBlock<W1: Widget, W2: Widget>(_ a: W1, _ b: W2) -> W1 { a }
    public static func buildBlock<W1: Widget, W2: Widget, W3: Widget>(
        _ a: W1, _ b: W2, _ c: W3) -> W1 { a }
}

public protocol WidgetBundle {
    // `some Widget`, as real WidgetKit declares it — not an array. A bundle written the real
    // way would not conform otherwise.
    associatedtype Body: Widget
    init()
    @WidgetBundleBuilder var body: Body { get }
}

extension WidgetBundle {
    /// What `@main` on a widget bundle actually resolves to. Without it the attribute is
    /// rejected for having no entry point, which is a fact about this shim and not the app.
    public static func main() {}
}

extension Widget {
    public static func main() {}
}

public enum WidgetCenter {
    public static let shared = WidgetCenter.Store()
    public struct Store {
        public func reloadAllTimelines() {}
        public func reloadTimelines(ofKind kind: String) {}
    }
}
