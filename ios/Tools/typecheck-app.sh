#!/bin/bash
# Type-check the iOS app and widget targets without an Apple SDK.
#
# Those targets need Xcode, so on Linux nothing compiles them — and the macOS job that could
# has been unable to start for most of this project's life. A commit once deleted five
# declarations from a widget file and left every call site; it survived five commits because
# the only build that would have failed was unavailable.
#
# What this does: aliases each Apple framework the app imports to a hand-written stand-in
# module, rebuilds the KIT against Security and Network stand-ins so its Darwin-only sources
# (the keychain store, the loopback listener) are real declarations rather than absent ones,
# and type-checks every app source against both.
#
# What it is not: the shims are permissive. Several arguments are loosely typed and modifiers
# return Self, so SwiftUI's real overload resolution is not reproduced and passing here is NOT
# a claim that Xcode compiles the same sources. The macOS job remains the authority.
#
# What it does catch, which nothing else here could: a name that no longer exists, a member
# that was renamed, a call whose labels or arity have drifted, a `body` returning something
# that is not a view, and — because the shim marks `View`, `App` and `Scene` as `@MainActor`
# like the real ones — actor-isolation mistakes under Swift 6 strict concurrency.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHIMS=ios/Tools/AppleShims
KIT_SOURCES=$(find ios/UsageLimitsKit/Sources -name '*.swift')
APP_SOURCES=$(find ios/UsageLimits/Sources -name '*.swift')
KIT_MODULE=$(mktemp -d)
trap 'rm -rf "$KIT_MODULE"' EXIT

swift build --package-path "$SHIMS" >/dev/null
SHIM_BUILD=$(swift build --package-path "$SHIMS" --show-bin-path)

ALIASES=(
  -module-alias SwiftUI=SwiftUIShim
  -module-alias WidgetKit=WidgetKitShim
  -module-alias UIKit=UIKitShim
  -module-alias AuthenticationServices=AuthenticationServicesShim
  -module-alias BackgroundTasks=BackgroundTasksShim
  -module-alias UserNotifications=UserNotificationsShim
  -module-alias Combine=CombineShim
)

# The kit, built as it would be on Apple hardware. `swift build` produces a Linux flavour in
# which `#if canImport(Security)` and `#if canImport(Network)` are false, so
# KeychainCredentialStore and LoopbackListener simply are not there — and the app files that
# use them could not be checked at all. Aliasing those two frameworks makes the real Darwin
# sources compile, which is also the only way this checks them.
echo "Building the kit with its Darwin sources…"
swiftc -emit-module -module-name UsageLimitsKit \
  -module-alias Security=SecurityShim -module-alias Network=NetworkShim \
  -I "$SHIM_BUILD/Modules" -I "$SHIM_BUILD" \
  -emit-module-path "$KIT_MODULE/UsageLimitsKit.swiftmodule" \
  $KIT_SOURCES

count=$(echo "$APP_SOURCES" | wc -l)
echo "Type-checking $count app sources against the framework shims…"
swiftc -typecheck "${ALIASES[@]}" \
  -I "$SHIM_BUILD/Modules" -I "$SHIM_BUILD" -I "$KIT_MODULE" \
  $APP_SOURCES

echo "OK — every name resolves, every call site matches, and nothing violates main-actor isolation."
