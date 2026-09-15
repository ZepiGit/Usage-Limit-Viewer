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
# Glance widget classes keep their names
#
# GlanceAppWidgetManager persists a map from each manifest receiver to the CANONICAL CLASS NAME
# of the GlanceAppWidget it renders, and `updateAll` looks the placed widgets up by that name.
# Glance's own consumer rules keep ActionCallback subclasses only. Without this rule the names
# are minified, and two builds need not minify them identically — after an update the stored
# map names classes that no longer exist, `updateAll` finds nothing to update, and every placed
# widget stays on its old numbers until the receiver next handles a system broadcast. Names
# only; the classes themselves are reachable and need no keep.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget

# ---------------------------------------------------------------------------------------------
# Deliberately absent
#
# Room, WorkManager, Glance and OkHttp all ship consumer rules that AGP applies automatically —
# Room's generated `_Impl` is reachable from the database class, WorkManager's rules keep the
# worker constructors its default factory instantiates reflectively, Glance's receivers are
# declared in the manifest (its widget classes are the one exception, above), and OkHttp has
# carried its own rules since 4.x. Adding keeps for them
# here would be cargo cult: untested, unnecessary, and impossible to justify removing later.
