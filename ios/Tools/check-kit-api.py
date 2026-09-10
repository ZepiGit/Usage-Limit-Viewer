#!/usr/bin/env python3
"""Check the iOS app against the kit's real, compiled public API.

The app and widget targets need Xcode, so on Linux nothing compiles them. The kit
does compile here, and `-emit-module-interface` writes exactly what it exports —
so the one boundary that can be checked without Apple's SDK is the app's use of
the kit. That boundary is also where drift actually hurts: renaming a kit member
leaves the app referring to something that no longer exists, and the failure
appears only on a macOS runner.

Two checks, both exact, neither needing type inference:

1. Every capitalised name the app uses resolves — to something the app declares,
   to a PUBLIC kit type, or to a framework name. Reading the public interface
   rather than the kit's sources is what makes this stronger than grep: a kit type
   that exists but is `internal` is invisible to the app and is reported here,
   where a source-level scan would call it fine.

2. Every `KitType.member` the app spells out exists on that type in the interface.
   Only references whose receiver is literally a kit type name are checked, which
   is why there is nothing to infer and nothing to get wrong.

Usage: check-kit-api.py [repo-root]
"""
import re
import sys
from pathlib import Path

APP = "ios/UsageLimits/Sources"
KIT_BUILD = "ios/UsageLimitsKit/.build"

DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|fileprivate\s+|open\s+)?"
    r"(?:final\s+)?(?:struct|enum|class|actor|protocol|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)
USE_TYPE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*(?:\(|\.)")
STRIP = re.compile(r'"(?:[^"\\]|\\.)*"|//[^\n]*|/\*.*?\*/', re.S)

IFACE_TYPE = re.compile(
    r"^(public|open)\s+(?:final\s+)?(?:struct|enum|class|actor|protocol|extension)\s+"
    r"([A-Za-z_][A-Za-z0-9_.]*)"
)
IFACE_MEMBER = re.compile(
    r"^\s+(?:@\w+\s+)*(?:public\s+|open\s+|package\s+)?(?:static\s+|class\s+)?"
    r"(?:final\s+)?(?:func|var|let|case|init|subscript)\s*([A-Za-z_][A-Za-z0-9_]*)?"
)

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


def find_interface(root):
    candidates = sorted((root / KIT_BUILD).rglob("UsageLimitsKit.swiftinterface"))
    return candidates[0] if candidates else None


def parse_interface(path):
    """Public type names, and the member names declared inside each."""
    types, members, current = set(), {}, None
    for line in path.read_text().splitlines():
        head = IFACE_TYPE.match(line)
        if head:
            # "extension Foo" and "struct Foo" both contribute members to Foo.
            name = head.group(2).split(".")[-1]
            types.add(name)
            current = name
            members.setdefault(name, set())
            continue
        if line.startswith("}"):
            current = None
            continue
        if current:
            member = IFACE_MEMBER.match(line)
            if member and member.group(1):
                members[current].add(member.group(1))
    return types, members


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    app_files = sorted((root / APP).rglob("*.swift"))
    if not app_files:
        print(f"no Swift sources under {root / APP}", file=sys.stderr)
        return 2

    interface = find_interface(root)
    if interface is None:
        print(
            "No UsageLimitsKit.swiftinterface found. Build the kit first:\n"
            "  swift build --package-path ios/UsageLimitsKit \\\n"
            "    -Xswiftc -emit-module-interface -Xswiftc -enable-library-evolution",
            file=sys.stderr,
        )
        return 2

    kit_types, kit_members = parse_interface(interface)

    app_declared = set()
    for path in app_files:
        app_declared |= set(DECL.findall(path.read_text()))

    known = app_declared | kit_types | FRAMEWORK
    unresolved, missing_members = [], []

    for path in app_files:
        code = STRIP.sub(" ", path.read_text())
        rel = path.relative_to(root)
        for name in sorted(set(USE_TYPE.findall(code))):
            if name not in known:
                unresolved.append((rel, name))
        for owner, member in sorted(set(re.findall(r"\b([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)", code))):
            if owner in kit_types and owner not in app_declared:
                if member not in kit_members.get(owner, set()):
                    missing_members.append((rel, owner, member))

    for path, name in unresolved:
        print(f"{path}: `{name}` is not declared in the app, not public in the kit, "
              f"and not a listed framework name")
    for path, owner, member in missing_members:
        print(f"{path}: `{owner}.{member}` — `{owner}` is a public kit type with no "
              f"public member `{member}`")

    total = len(unresolved) + len(missing_members)
    print(f"\n{len(app_files)} app files checked against {len(kit_types)} public kit types, "
          f"{total} problem(s)")
    if total:
        print(
            "A hit is one of: a declaration that was deleted or never written (a real\n"
            "build failure), a kit symbol that is internal rather than public, or a\n"
            "framework name used here for the first time — add that to FRAMEWORK."
        )
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
