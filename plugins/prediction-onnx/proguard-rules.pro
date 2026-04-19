# Plugin ProGuard rules
# CRITICAL: Disable ALL optimizations for compatibility with host app
-dontoptimize
-dontobfuscate

# Keep ALL Kotlin classes and members
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep interface kotlin.** { *; }

# Keep ALL Compose classes
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep ALL kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep plugin classes
-keep class com.kingzcheung.kime.plugin.prediction.** { *; }
-keepclassmembers class com.kingzcheung.kime.plugin.prediction.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable