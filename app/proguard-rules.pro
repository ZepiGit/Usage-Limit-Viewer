# R8 rules for the release build.
#
# Everything here is a rule that is REQUIRED, with the failure it prevents named. A proguard file
# full of speculative keeps is a file nobody can prune later, and each unnecessary keep is code
# that ships for no reason.

# ---------------------------------------------------------------------------------------------
# kotlinx.serialization
#
# The one library here that R8 genuinely breaks. `serializer()` resolves the generated
# `$$serializer` class and the `Companion.serializer()` function REFLECTIVELY at runtime, so the
# shrinker sees them as unreachable and removes them. The symptom is not a build failure: the app
# ships, and then throws on the first payload it parses — which for this app is every screen.
#
# Scoped to this package rather than applied globally, so it keeps what is actually serialised
# and nothing else.
-keep,includedescriptorclasses class com.usagelimits.**$$serializer { *; }
-keepclassmembers class com.usagelimits.** {
    *** Companion;
}
-keepclasseswithmembers class com.usagelimits.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------------------------
# Crash reports that can be read
#
# Without these, a stack trace from a release build names obfuscated symbols and line numbers
# that do not exist. `mapping.txt` is retained by the release job for the same reason.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------------
# Deliberately absent
#
# Room, WorkManager, Glance and OkHttp all ship consumer rules that AGP applies automatically —
# Room's generated `_Impl` is reachable from the database class, WorkManager's rules keep the
# worker constructors its default factory instantiates reflectively, Glance's providers are
# declared in the manifest, and OkHttp has carried its own rules since 4.x. Adding keeps for them
# here would be cargo cult: untested, unnecessary, and impossible to justify removing later.
