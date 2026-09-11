// The concrete views, property wrappers and framework types the app names.
//
// Deliberately minimal: each exists so a reference to it resolves and its call sites are
// checked for arity and labels. None of them renders anything.
@_exported import Foundation

// MARK: - Property wrappers

@propertyWrapper
public struct State<Value> {
    // Boxed so the setter can be `nonmutating`, exactly as real SwiftUI's is. Without that,
    // assigning to an `@State` property from inside a `body` — which every button in this app
    // does — fails with "self is immutable", an error that exists only in the shim.
    private final class Box { var value: Value; init(_ value: Value) { self.value = value } }
    private let box: Box
    public init(wrappedValue: Value) { box = Box(wrappedValue) }
    public init(initialValue: Value) { box = Box(initialValue) }
    public var wrappedValue: Value {
        get { box.value }
        nonmutating set { box.value = newValue }
    }
    public var projectedValue: Binding<Value> {
        Binding(get: { box.value }, set: { box.value = $0 })
    }
}

public struct ButtonRole {
    public static let destructive = ButtonRole()
    public static let cancel = ButtonRole()
}

public struct ButtonStyleShim {
    public static let bordered = ButtonStyleShim()
    public static let borderedProminent = ButtonStyleShim()
    public static let borderless = ButtonStyleShim()
    public static let plain = ButtonStyleShim()
    public static let automatic = ButtonStyleShim()
}

public struct PickerStyleShim {
    public static let segmented = PickerStyleShim()
    public static let menu = PickerStyleShim()
    public static let inline = PickerStyleShim()
    public static let wheel = PickerStyleShim()
}

public struct ListStyleShim {
    public static let plain = ListStyleShim()
    public static let insetGrouped = ListStyleShim()
    public static let grouped = ListStyleShim()
}

@propertyWrapper
@dynamicMemberLookup
public struct Binding<Value> {
    private let get: () -> Value
    private let set: (Value) -> Void
    public init(get: @escaping () -> Value, set: @escaping (Value) -> Void) {
        self.get = get
        self.set = set
    }
    public var wrappedValue: Value {
        nonmutating get { get() }
        nonmutating set { set(newValue) }
    }
    public var projectedValue: Binding<Value> { self }
    public static func constant(_ value: Value) -> Binding<Value> {
        Binding(get: { value }, set: { _ in })
    }

    /// `$settings.syncIntervalMinutes` — a binding into a field of the bound value, which is
    /// how the settings screen drives every control it owns.
    public subscript<Subject>(dynamicMember keyPath: WritableKeyPath<Value, Subject>) -> Binding<Subject> {
        Binding<Subject>(get: { fatalError("not resolved in the shim") }, set: { _ in })
    }
}

public enum Visibility { case automatic, visible, hidden }


/// The `$store.thing` form. Real SwiftUI vends a dynamic-member wrapper that turns a keypath
/// into a `Binding`; without it every `$`-prefixed use in the app fails to resolve.
@dynamicMemberLookup
public struct ObservableWrapper<Value: AnyObject> {
    public init() {}
    public subscript<Subject>(
        dynamicMember keyPath: ReferenceWritableKeyPath<Value, Subject>
    ) -> Binding<Subject> {
        Binding(get: { fatalError("not resolved in the shim") }, set: { _ in })
    }
}

@propertyWrapper
public struct StateObject<Value: AnyObject> {
    public var wrappedValue: Value
    public init(wrappedValue: @autoclosure @escaping () -> Value) { self.wrappedValue = wrappedValue() }
    public var projectedValue: ObservableWrapper<Value> { .init() }
}

@propertyWrapper
public struct ObservedObject<Value: AnyObject> {
    public var wrappedValue: Value
    public init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
    public var projectedValue: ObservableWrapper<Value> { .init() }
}

@propertyWrapper
public struct EnvironmentObject<Value: AnyObject> {
    public var wrappedValue: Value { fatalError("not resolved in the shim") }
    public init() {}
    public var projectedValue: ObservableWrapper<Value> { .init() }
}

@propertyWrapper
public struct Environment<Value> {
    public var wrappedValue: Value { fatalError("not resolved in the shim") }
    public init(_ keyPath: KeyPath<EnvironmentValues, Value>) {}
}

@propertyWrapper
public struct ScaledMetric<Value: BinaryFloatingPoint> {
    public var wrappedValue: Value
    public init(wrappedValue: Value, relativeTo: Font.TextStyle? = nil) {
        self.wrappedValue = wrappedValue
    }
}

@propertyWrapper
public struct Published<Value> {
    public var wrappedValue: Value
    public init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
    public var projectedValue: Published<Value> { self }
}

public protocol ObservableObject: AnyObject {}

// MARK: - Containers and leaves

public struct VStack: View {
    public init(alignment: HorizontalAlignment? = nil, spacing: CGFloat? = nil,
                @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct HStack: View {
    public init(alignment: VerticalAlignment? = nil, spacing: CGFloat? = nil,
                @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct ZStack: View {
    public init(alignment: Alignment? = nil, @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct LazyVStack: View {
    public init(alignment: HorizontalAlignment? = nil, spacing: CGFloat? = nil,
                @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Group: View {
    public init(@ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Section: View {
    public init(@ViewBuilder content: () -> any View) {}
    public init(_ title: Any, @ViewBuilder content: () -> any View) {}
    public init(header: Any, @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Form: View {
    public init(@ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct List: View {
    public init(@ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct ScrollView: View {
    public init(_ axes: Any? = nil, showsIndicators: Bool = true,
                @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct ForEach<Data: RandomAccessCollection, Content>: View {
    public init(_ data: Data, @ViewBuilder content: @escaping (Data.Element) -> Content) {}
    public init(_ data: Data, id: KeyPath<Data.Element, some Hashable>,
                @ViewBuilder content: @escaping (Data.Element) -> Content) {}
    public var body: some View { ShimLeaf() }
}

public enum DynamicTypeSize: Comparable {
    case xSmall, small, medium, large, xLarge, xxLarge, xxxLarge
    case accessibility1, accessibility2, accessibility3, accessibility4, accessibility5
}

public extension FileManager {
    /// The App Group container. Absent from swift-corelibs Foundation, and the app's only
    /// route to the directory the widget reads.
    func containerURL(forSecurityApplicationGroupIdentifier identifier: String) -> URL? { nil }
}

public struct Text: View {
    public init(_ content: String) {}
    public init(verbatim: String) {}
    public init(_ date: Date, style: Any) {}
    public var body: some View { ShimLeaf() }
}

public struct Image: View {
    public init(systemName: String) {}
    public init(_ name: String) {}
    public var body: some View { ShimLeaf() }
    public func resizable() -> Image { self }
}

public struct Button: View {
    public init(action: @escaping () -> Void, @ViewBuilder label: () -> any View) {}
    public init(_ title: String, action: @escaping () -> Void) {}
    public init(_ title: String, role: ButtonRole?, action: @escaping () -> Void) {}
    public init(role: ButtonRole?, action: @escaping () -> Void,
                @ViewBuilder label: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Label: View {
    public init(_ title: String, systemImage: String) {}
    public var body: some View { ShimLeaf() }
}

public struct Toggle: View {
    public init(_ title: String, isOn: Binding<Bool>) {}
    public init(isOn: Binding<Bool>, @ViewBuilder label: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Picker<SelectionValue>: View {
    public init(_ title: String, selection: Binding<SelectionValue>,
                @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Menu: View {
    public init(@ViewBuilder content: () -> any View, @ViewBuilder label: () -> any View) {}
    public init(_ title: String, @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Spacer: View {
    public init(minLength: Any? = nil) {}
    public var body: some View { ShimLeaf() }
}

public struct Divider: View {
    public init() {}
    public var body: some View { ShimLeaf() }
}

public struct ProgressView: View {
    public init() {}
    public init(value: Any?, total: Any? = nil) {}
    public var body: some View { ShimLeaf() }
}

public struct Capsule: View {
    public init() {}
    public var body: some View { ShimLeaf() }
}

public struct Circle: View {
    public init() {}
    public var body: some View { ShimLeaf() }
}

public struct RoundedRectangle: View {
    public init(cornerRadius: CGFloat, style: RoundedCornerStyle? = nil) {}
    public var body: some View { ShimLeaf() }
}

public struct Rectangle: View {
    public init() {}
    public var body: some View { ShimLeaf() }
}

public struct GeometryReader: View {
    public init(@ViewBuilder content: @escaping (GeometryProxy) -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct GeometryProxy {
    public var size: CGSize { .zero }
}

// MARK: - App entry point

@MainActor
public protocol Scene {
    associatedtype Body: Scene
    @SceneBuilder var body: Body { get }
}

@resultBuilder
@MainActor
public enum SceneBuilder {
    public static func buildBlock<S: Scene>(_ scene: S) -> S { scene }
    public static func buildBlock<S1: Scene, S2: Scene>(_ a: S1, _ b: S2) -> S1 { a }
}

public struct WindowGroup: Scene {
    public init(@ViewBuilder content: () -> any View) {}
    public var body: some Scene { self }
}

@MainActor
public protocol App {
    associatedtype Body: Scene
    init()
    @SceneBuilder var body: Body { get }
}

extension App {
    /// What `@main` on an `App` resolves to. Without it the attribute is rejected for having
    /// no entry point — a fact about this shim, not about the app.
    public static func main() {}
}

public struct NavigationStack: View {
    public init(@ViewBuilder root: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct TabView: View {
    public init(selection: Any? = nil, @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct ToolbarItem: View {
    public init(placement: ToolbarItemPlacement? = nil, @ViewBuilder content: () -> any View) {}
    public var body: some View { ShimLeaf() }
}

public struct Alignment {
    public static let leading = Alignment(); public static let center = Alignment()
    public static let trailing = Alignment(); public static let top = Alignment()
    public static let bottom = Alignment(); public static let topLeading = Alignment()
    public static let topTrailing = Alignment(); public static let bottomLeading = Alignment()
    public static let bottomTrailing = Alignment()
}

public struct HorizontalAlignment {
    public static let leading = HorizontalAlignment(); public static let center = HorizontalAlignment()
    public static let trailing = HorizontalAlignment()
}

public struct VerticalAlignment {
    public static let top = VerticalAlignment(); public static let center = VerticalAlignment()
    public static let bottom = VerticalAlignment(); public static let firstTextBaseline = VerticalAlignment()
    public static let lastTextBaseline = VerticalAlignment()
}

public struct RoundedCornerStyle {
    public static let circular = RoundedCornerStyle()
    public static let continuous = RoundedCornerStyle()
}

public struct Edge {
    public struct Set: OptionSet, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let top = Set(rawValue: 1); public static let leading = Set(rawValue: 2)
        public static let bottom = Set(rawValue: 4); public static let trailing = Set(rawValue: 8)
        public static let horizontal: Set = [.leading, .trailing]
        public static let vertical: Set = [.top, .bottom]
        public static let all: Set = [.top, .leading, .bottom, .trailing]
    }
    public static let top = Edge(); public static let leading = Edge()
    public static let bottom = Edge(); public static let trailing = Edge()
}

public struct Color {
    public struct RGBColorSpace { public static let sRGB = RGBColorSpace()
        public static let sRGBLinear = RGBColorSpace(); public static let displayP3 = RGBColorSpace() }
    public init(red: Double, green: Double, blue: Double, opacity: Double = 1) {}
    public init(_ space: RGBColorSpace, red: Double, green: Double, blue: Double, opacity: Double = 1) {}
    public init(_ name: String) {}
    public static let clear = Color("clear")
    public static let white = Color("white")
    public static let black = Color("black")
    public static let primary = Color("primary")
    public static let secondary = Color("secondary")
    public static let red = Color("red")
    public static let green = Color("green")
    public static let orange = Color("orange")
    public static let blue = Color("blue")
    public static let gray = Color("gray")
    public func opacity(_ value: Double) -> Color { self }
}

extension Color: View {
    public var body: some View { ShimLeaf() }
}

public protocol ShapeStyle {}

public struct HierarchicalShapeStyle: ShapeStyle {
    public init() {}
}

extension ShapeStyle where Self == HierarchicalShapeStyle {
    public static var primary: HierarchicalShapeStyle { .init() }
    public static var secondary: HierarchicalShapeStyle { .init() }
    public static var tertiary: HierarchicalShapeStyle { .init() }
    public static var quaternary: HierarchicalShapeStyle { .init() }
}

extension Color: ShapeStyle {}

public struct DismissAction {
    public func callAsFunction() {}
}

public struct OpenURLAction {
    public func callAsFunction(_ url: URL) {}
}

public struct ToolbarItemPlacement {
    public static let cancellationAction = ToolbarItemPlacement()
    public static let confirmationAction = ToolbarItemPlacement()
    public static let primaryAction = ToolbarItemPlacement()
    public static let topBarLeading = ToolbarItemPlacement()
    public static let topBarTrailing = ToolbarItemPlacement()
    public static let navigationBarLeading = ToolbarItemPlacement()
    public static let navigationBarTrailing = ToolbarItemPlacement()
}

public enum TextSelectability { case enabled, disabled }

public struct EnvironmentValues {
    public var dismiss = DismissAction()
    public var openURL = OpenURLAction()
    public var dynamicTypeSize: DynamicTypeSize = .large
    public var colorScheme: ColorScheme = .dark
    public var scenePhase: ScenePhase = .active
    public var widgetFamily: WidgetFamilyPlaceholder = .systemSmall
}

public enum ColorScheme { case light, dark }

/// The widget family, declared here because `EnvironmentValues` lives in this module while
/// `WidgetFamily` belongs to the WidgetKit shim; the alias there keeps the app's spelling.
public enum WidgetFamilyPlaceholder {
    case systemSmall, systemMedium, systemLarge, systemExtraLarge
    case accessoryRectangular, accessoryCircular, accessoryInline
}

/// Where a container background applies. The app calls `containerBackground(for: .widget)`.
public struct ContainerBackgroundPlacement {
    public static let widget = ContainerBackgroundPlacement()
}

/// Backgrounds a widget can ask for. `.widget` is the one the app uses.
public struct WidgetBackgroundStyle: ShapeStyle { public init() {} }

extension ShapeStyle where Self == WidgetBackgroundStyle {
    public static var widget: WidgetBackgroundStyle { .init() }
}
public enum ScenePhase { case active, inactive, background }

public struct Font {
    public enum TextStyle {
        case largeTitle, title, title2, title3, headline, subheadline
        case body, callout, footnote, caption, caption2
    }
    public static let headline = Font()
    public static let subheadline = Font()
    public static let footnote = Font()
    public static let caption = Font()
    public static let caption2 = Font()
    public static let body = Font()
    public static let title = Font()
    public static let title2 = Font()
    public static let title3 = Font()
    public static let largeTitle = Font()
    public static func system(size: CGFloat, weight: Weight? = nil, design: Design? = nil) -> Font { Font() }
    public static func system(_ style: TextStyle, design: Design? = nil, weight: Weight? = nil) -> Font { Font() }
    public func weight(_ value: Weight) -> Font { self }
    public func monospacedDigit() -> Font { self }
    public struct Weight { public static let regular = Weight(); public static let medium = Weight()
        public static let semibold = Weight(); public static let bold = Weight() }
    public struct Design {
        public static let rounded = Design(); public static let `default` = Design()
        public static let monospaced = Design(); public static let serif = Design()
    }
}

public struct CGSize {
    public var width: Double
    public var height: Double
    public static let zero = CGSize(width: 0, height: 0)
}

public typealias CGFloat = Double


/// Foundation constants the app uses that swift-corelibs does not surface here.
public let NSEC_PER_SEC: UInt64 = 1_000_000_000
public let NSEC_PER_MSEC: UInt64 = 1_000_000


/// Text entry, for the one screen that has any: pasting a Kimi Code key.
public struct TextField: View {
    public init(_ title: String, text: Binding<String>) {}
    public init(_ title: String, text: Binding<String>, prompt: Any?) {}
    public var body: some View { ShimLeaf() }
}

public struct TextFieldStyleShim {
    public static let roundedBorder = TextFieldStyleShim()
    public static let plain = TextFieldStyleShim()
    public static let automatic = TextFieldStyleShim()
}

/// `.never` is the only case the app uses; the rest exist so a later change type-checks.
public struct TextInputAutocapitalizationShim {
    public static let never = TextInputAutocapitalizationShim()
    public static let words = TextInputAutocapitalizationShim()
    public static let sentences = TextInputAutocapitalizationShim()
    public static let characters = TextInputAutocapitalizationShim()
}
