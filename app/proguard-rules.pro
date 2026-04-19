# App ProGuard rules
# Disable obfuscation (keep class names for debugging)
-dontobfuscate

# CRITICAL: Only disable Lambda merging optimization
# This prevents signature mismatch between host app and plugins
-optimizations !class/merging/*

# Keep Kotlin stdlib - plugins may use any Kotlin API
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }

# Keep Compose classes - plugins use Compose UI
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep plugin API classes
-keep class com.kingzcheung.kime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.kime.plugin.** { *; }

# Keep Rime native classes
-keep class com.kingzcheung.kime.rime.** { *; }
-keep class com.kingzcheung.kime.**Jni** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Remove Kotlin null checks (safe optimization)
-processkotlinnullchecks remove