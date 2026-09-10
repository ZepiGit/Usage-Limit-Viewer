#!/usr/bin/env python3
"""Find Swift type references in the iOS app that resolve to no declaration.

The app and widget targets need Xcode. On Linux — and in any CI run that cannot
reach a macOS runner — nothing compiles them at all, so a block edit that deletes a
declaration while leaving its call sites lands, reviews clean, and fails only at the
one build nobody could run. That is not hypothetical: a single commit took five
declarations out of one widget file, and every use of them stayed.

The check is deliberately crude and complete rather than clever: collect every type
declared in the app and the kit, collect every `Foo(` / `Foo.` reference in the app,
and report references that match neither those nor the framework names below. A hit
is one of two things — a type that was deleted or never written, which is a real
build failure; or a framework type used here for the first time, which belongs in
FRAMEWORK. Both want a human decision, which is why neither is guessed at.

Strings and comments are stripped first: a doc comment naming a type it no longer
uses is not a reference.
"""
import re
import sys
from pathlib import Path

APP = "ios/UsageLimits/Sources"
KIT = "ios/UsageLimitsKit/Sources"

DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|fileprivate\s+|open\s+)?"
    r"(?:final\s+)?(?:struct|enum|class|actor|protocol|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)
USE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*(?:\(|\.)")
STRIP = re.compile(r'"(?:[^"\\]|\\.)*"|//[^\n]*|/\*.*?\*/', re.S)

# Swift, SwiftUI, WidgetKit, UIKit, Foundation and friends. Additions are expected;
# each one should be a name you can point at in Apple's documentation.
FRAMEWORK = {
    "ASPresentationAnchor", "ASWebAuthenticationSession", "Array",
    "BGAppRefreshTaskRequest", "BGTaskScheduler", "Button", "CGFloat",
    "CancellationError", "Capsule", "Circle", "Color", "Date", "Divider", "Double",
    "Environment", "FileManager", "ForEach", "HStack", "Image", "Int", "Label",
    "LazyVStack", "MainActor", "Picker", "ProcessInfo", "ProgressView",
    "RoundedRectangle", "ScaledMetric", "Section", "Self", "Spacer",
    "StaticConfiguration", "String", "Task", "Text", "TimeInterval", "Timeline",
    "Toggle", "ToolbarItem", "UIApplication", "UNMutableNotificationContent",
    "UNNotificationRequest", "UNUserNotificationCenter", "URL", "VStack", "ZStack",
}


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    app_files = sorted((root / APP).rglob("*.swift"))
    if not app_files:
        print(f"no Swift sources under {root / APP}", file=sys.stderr)
        return 2

    declared = set()
    for source in (root / APP, root / KIT):
        for path in source.rglob("*.swift"):
            declared |= set(DECL.findall(path.read_text()))

    known = declared | FRAMEWORK
    problems = []
    for path in app_files:
        code = STRIP.sub(" ", path.read_text())
        for name in sorted(set(USE.findall(code))):
            if name not in known:
                problems.append((path.relative_to(root), name))

    for path, name in problems:
        print(f"{path}: `{name}` is used but declared nowhere")
    print(f"\n{len(app_files)} files scanned, {len(problems)} unresolved reference(s)")
    if problems:
        print(
            "Each is either a deleted or missing declaration — a real build failure —"
            "\nor a framework type used here for the first time, which belongs in"
            "\nFRAMEWORK in this script."
        )
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
