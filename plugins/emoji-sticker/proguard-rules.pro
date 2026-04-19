# Plugin ProGuard rules

# disable obfuscation
-dontobfuscate
# disable optimizations - CRITICAL for plugin compatibility with host app
-dontoptimize

# Keep Kotlin standard library - CRITICAL for plugin compatibility
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlin.jvm.** { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.collections.** { *; }
-keep class kotlin.collections.deserializations.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.coroutines.intrinsics.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.text.** { *; }
-keep class kotlin.internal.** { *; }
-keep class kotlin.experimental.** { *; }
-keep class kotlin.sequences.** { *; }
-keep class kotlin.time.** { *; }

# Keep Kotlin inline functions
-keep class kotlin.coroutines.Intrinsics { *; }
-keep class kotlin.coroutines.RestrictedSuspension { *; }
-keep class kotlin.coroutines.SafeContinuation { *; }

# Keep kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# Keep Compose classes - CRITICAL for plugin UI compatibility
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep all plugin classes
-keep class com.kingzcheung.kime.plugin.emoji.** { *; }

# remove kotlin null checks
-processkotlinnullchecks remove

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable