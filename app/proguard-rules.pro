# App ProGuard rules
# CRITICAL: Must disable ALL optimizations for plugin compatibility
# This overrides proguard-android-optimize.txt defaults
-dontoptimize
-dontobfuscate

# Keep ALL Kotlin classes and members - plugins may use any API
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep interface kotlin.** { *; }

# Keep ALL Compose classes - plugins use Compose UI
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep ALL kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# Keep plugin API classes
-keep class com.kingzcheung.kime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.kime.plugin.** { *; }
-keep interface com.kingzcheung.kime.plugin.** { *; }

# Keep Rime native classes
-keep class com.kingzcheung.kime.rime.** { *; }
-keep class com.kingzcheung.kime.**Jni** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Remove Kotlin null checks (optional optimization)
-processkotlinnullchecks remove