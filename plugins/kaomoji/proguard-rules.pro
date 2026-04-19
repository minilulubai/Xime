# Plugin ProGuard rules
# Disable obfuscation
-dontobfuscate

# CRITICAL: Only disable Lambda merging optimization
-optimizations !class/merging/*

# Keep Kotlin stdlib
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }

# Keep Compose classes
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep plugin classes
-keep class com.kingzcheung.kime.plugin.kaomoji.** { *; }
-keepclassmembers class com.kingzcheung.kime.plugin.kaomoji.** { *; }

# Preserve line numbers
-keepattributes SourceFile,LineNumberTable