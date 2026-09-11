// A stand-in for the Apple frameworks the app imports, so the app and widget TARGETS can be
// type-checked on a machine with no Apple SDK.
//
// Why this exists: those targets need Xcode, so on Linux nothing compiles them. It was
// written while the one job that could was unable to start — a commit had deleted five
// declarations from a widget file and left every call site, and it sat in the tree for five
// commits because the only build that would have failed was unavailable. Grep-level checks
// caught that particular shape and nothing else. The macOS job runs now; this stays as the
// check that fails first and cheapest.
//
// What it is and is not. It is permissive on purpose: every modifier returns `Self`, and
// arguments are widely typed, so this does NOT reproduce SwiftUI's overload resolution and
// passing it is not a claim that Xcode would compile the same sources. What it does catch is
// the class of failure that actually happens here — a name that does not exist, a member that
// was renamed, a call whose labels or arity no longer match, a `some View` body that returns
// something which is not a view — across OUR code and the kit's real API.
//
// Anything it reports is worth reading. Anything it misses, the macOS job still owns.
import Foundation

// MARK: - View

/// `@MainActor`, as real SwiftUI declares it.
///
/// Not cosmetic: this project builds with Swift 6 strict concurrency, and the isolation of
/// `body` is what makes a call from it to main-actor state legal. Without the annotation the
/// app's perfectly correct code reports isolation errors that exist only here — and, worse,
/// a genuine isolation mistake would go unreported.
@MainActor
public protocol View {
    associatedtype Body: View
    // `@ViewBuilder`, as in real SwiftUI, and not optional: without it an `if`/`else` in a
    // `body` demands both branches have the same type, and the app's perfectly ordinary
    // conditional views fail to type-check for a reason that exists only in this shim.
    @ViewBuilder var body: Body { get }
}

/// The leaf every stub returns.
///
/// Real SwiftUI uses `Never` here, whose `body` traps. That cannot work under a
/// `@ViewBuilder` requirement — the transform rewrites the trap into a call returning a view,
/// which does not type-check against `Never`. A self-returning leaf satisfies the recursion
/// instead, and nothing in this package is ever rendered.
public struct ShimLeaf: View {
    public init() {}
    // Opaque, like every other stub here. Declaring the concrete `ShimLeaf` made the builder
    // transform's result — which it is free to widen — fail to match the annotation.
    public var body: some View { EmptyShimView() }
}

public struct AnyShimView: View {
    public init() {}
    public init(_ any: Any) {}
    public var body: some View { ShimLeaf() }
}

public struct EmptyShimView: View {
    public init() {}
    public var body: some View { ShimLeaf() }
}

@resultBuilder
@MainActor
public enum ViewBuilder {
    public static func buildBlock() -> EmptyShimView { EmptyShimView() }
    public static func buildBlock<V: View>(_ view: V) -> V { view }
    public static func buildBlock(_ views: any View...) -> AnyShimView { AnyShimView() }
    public static func buildOptional(_ view: (any View)?) -> AnyShimView { AnyShimView() }
    public static func buildEither<V: View>(first: V) -> AnyShimView { AnyShimView() }
    public static func buildEither<V: View>(second: V) -> AnyShimView { AnyShimView() }
    public static func buildArray(_ views: [any View]) -> AnyShimView { AnyShimView() }
    public static func buildExpression<V: View>(_ view: V) -> V { view }
    public static func buildExpression(_ view: any View) -> AnyShimView { AnyShimView() }
    public static func buildLimitedAvailability(_ view: any View) -> AnyShimView { AnyShimView() }
}

// Every modifier the app uses, returning Self so chains type-check without modelling
// SwiftUI's real modifier types.
extension View {
    public func font(_ value: Font?) -> Self { self }
    public func accessibilityElement(children: Any? = nil) -> Self { self }
    public func accessibilityHidden(_ value: Bool) -> Self { self }
    public func accessibilityValue(_ value: Any) -> Self { self }
    public func accessibilityAddTraits(_ value: Any) -> Self { self }
    public func dynamicTypeSize(_ value: Any) -> Self { self }
    public func privacySensitive(_ value: Bool = true) -> Self { self }
    public func unredacted() -> Self { self }
    public func foregroundStyle<S: ShapeStyle>(_ value: S) -> Self { self }
    public func foregroundColor(_ value: Color?) -> Self { self }
    public func padding(_ value: CGFloat? = nil) -> Self { self }
    public func padding(_ edges: Edge.Set, _ length: CGFloat? = nil) -> Self { self }
    public func lineLimit(_ value: Int?) -> Self { self }
    public func frame(width: CGFloat? = nil, height: CGFloat? = nil,
                      alignment: Alignment? = nil) -> Self { self }
    public func frame(minWidth: CGFloat? = nil, idealWidth: CGFloat? = nil, maxWidth: CGFloat? = nil,
                      minHeight: CGFloat? = nil, idealHeight: CGFloat? = nil, maxHeight: CGFloat? = nil,
                      alignment: Alignment? = nil) -> Self { self }
    public func background(_ value: Any) -> Self { self }
    public func background(_ value: Any, in shape: Any) -> Self { self }
    public func cornerRadius(_ radius: CGFloat, style: RoundedCornerStyle? = nil) -> Self { self }
    public func tint(_ value: Any?) -> Self { self }
    public func tabItem(@ViewBuilder content: () -> any View) -> Self { self }
    public func preferredColorScheme(_ value: ColorScheme?) -> Self { self }
    public func badge(_ value: Any?) -> Self { self }
    public func accessibilityLabel(_ value: Any) -> Self { self }
    public func accessibilityIdentifier(_ value: String) -> Self { self }
    public func buttonStyle(_ value: ButtonStyleShim) -> Self { self }
    public func navigationTitle(_ value: Any) -> Self { self }
    public func listRowBackground(_ value: Any?) -> Self { self }
    public func listRowSeparator(_ value: Visibility) -> Self { self }
    public func listRowInsets(_ value: EdgeInsets?) -> Self { self }
    public func moveDisabled(_ value: Bool) -> Self { self }
    public func deleteDisabled(_ value: Bool) -> Self { self }
    public func environment<T>(_ keyPath: WritableKeyPath<EnvironmentValues, T>,
                               _ value: T) -> Self { self }
    public func listStyle(_ value: ListStyleShim) -> Self { self }
    public func scrollContentBackground(_ value: Visibility) -> Self { self }
    public func toolbar(@ViewBuilder content: () -> any View) -> Self { self }
    public func toolbarBackground(_ visibility: Visibility, for bars: Any) -> Self { self }
    public func toolbarBackground<S: ShapeStyle>(_ style: S, for bars: Any) -> Self { self }
    public func sheet(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil,
                      @ViewBuilder content: () -> any View) -> Self { self }
    public func alert(_ title: Any, isPresented: Binding<Bool>,
                      @ViewBuilder actions: () -> any View) -> Self { self }
    public func confirmationDialog(_ title: Any, isPresented: Binding<Bool>,
                                   titleVisibility: Visibility? = nil,
                                   @ViewBuilder actions: () -> any View) -> Self { self }
    public func confirmationDialog(_ title: Any, isPresented: Binding<Bool>,
                                   titleVisibility: Visibility? = nil,
                                   @ViewBuilder actions: () -> any View,
                                   @ViewBuilder message: () -> any View) -> Self { self }
    public func refreshable(action: @escaping () async -> Void) -> Self { self }
    public func task(_ action: @escaping () async -> Void) -> Self { self }
    public func task<T: Equatable>(id: T, _ action: @escaping () async -> Void) -> Self { self }
    public func onAppear(perform: (() -> Void)? = nil) -> Self { self }
    public func onDisappear(perform: (() -> Void)? = nil) -> Self { self }
    public func onChange<T: Equatable>(of value: T, perform: @escaping (T) -> Void) -> Self { self }
    public func onOpenURL(perform: @escaping (URL) -> Void) -> Self { self }
    public func environmentObject(_ object: Any) -> Self { self }
    public func disabled(_ value: Bool) -> Self { self }
    public func opacity(_ value: Double) -> Self { self }
    public func overlay(_ value: Any) -> Self { self }
    public func overlay(@ViewBuilder content: () -> any View) -> Self { self }
    public func clipShape(_ shape: Any) -> Self { self }
    public func cornerRadius(_ radius: Any) -> Self { self }
    public func contentShape(_ shape: Any) -> Self { self }
    public func fixedSize(horizontal: Bool = false, vertical: Bool = false) -> Self { self }
    public func multilineTextAlignment(_ value: Any) -> Self { self }
    public func textCase(_ value: Any?) -> Self { self }
    public func monospacedDigit() -> Self { self }
    public func textSelection(_ value: TextSelectability) -> Self { self }
    public func monospaced(_ enabled: Bool = true) -> Self { self }
    public func redacted(reason: Any) -> Self { self }
    public func id<T: Hashable>(_ value: T) -> Self { self }
    public func tag<T: Hashable>(_ value: T) -> Self { self }
    public func labelsHidden() -> Self { self }
    public func textFieldStyle(_ value: TextFieldStyleShim) -> Self { self }
    public func autocorrectionDisabled(_ disabled: Bool = true) -> Self { self }
    public func textInputAutocapitalization(
        _ value: TextInputAutocapitalizationShim?
    ) -> Self { self }
    public func pickerStyle(_ value: PickerStyleShim) -> Self { self }
    public func containerBackground<S: ShapeStyle>(_ style: S, for container: Any) -> Self { self }
    public func containerBackground(for container: ContainerBackgroundPlacement,
                                    @ViewBuilder content: () -> any View) -> Self { self }
    public func widgetURL(_ url: URL?) -> Self { self }
    public func fill(_ value: Any) -> Self { self }
    public func stroke(_ value: Any, lineWidth: Any? = nil) -> Self { self }
    public func stroke<S: ShapeStyle>(_ content: S, style: StrokeStyle) -> Self { self }
    public func trim(from: CGFloat, to: CGFloat) -> Self { self }
    public func rotationEffect(_ angle: Angle, anchor: Any? = nil) -> Self { self }
    public func symbolRenderingMode(_ value: Any) -> Self { self }
    public func imageScale(_ value: Any) -> Self { self }
    public func bold() -> Self { self }
    public func italic() -> Self { self }
    public func underline() -> Self { self }
    public func minimumScaleFactor(_ value: Double) -> Self { self }
    public func truncationMode(_ value: Any) -> Self { self }
    public func animation(_ value: Any?, value other: Any) -> Self { self }
    public func transition(_ value: Any) -> Self { self }
    public func zIndex(_ value: Double) -> Self { self }
    public func offset(x: Any? = nil, y: Any? = nil) -> Self { self }
    public func scaleEffect(_ value: Any) -> Self { self }
    public func rotationEffect(_ value: Any) -> Self { self }
    public func shadow(color: Any? = nil, radius: Any, x: Any? = nil, y: Any? = nil) -> Self { self }
    public func border(_ value: Any, width: Any? = nil) -> Self { self }
    public func safeAreaInset(edge: Any, @ViewBuilder content: () -> any View) -> Self { self }
    public func swipeActions(edge: Any? = nil, allowsFullSwipe: Bool = true,
                             @ViewBuilder content: () -> any View) -> Self { self }
}
