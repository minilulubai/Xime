# disable obfuscation
-dontobfuscate
# disable optimizations (keep all code paths)
-dontoptimize

# Keep Kotlin standard library
-keep class kotlin.** { *; }
-keep class kotlin.jvm.** { *; }
-keep class kotlin.collections.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.reflect.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# CRITICAL: Keep plugin classes for discovery
-keep class com.kingzcheung.kime.plugin.funasr.PluginDeclaration { *; }
-keep class com.kingzcheung.kime.plugin.funasr.FunAsrPluginFactory { *; }
-keep class com.kingzcheung.kime.plugin.funasr.FunAsrSpeechPlugin { *; }
-keep class com.kingzcheung.kime.plugin.funasr.WebSocketManager { *; }
-keep class com.kingzcheung.kime.plugin.funasr.FunAsrPreferences { *; }
-keep class com.kingzcheung.kime.plugin.funasr.PluginSettingsActivity { *; }
-keep class com.kingzcheung.kime.plugin.funasr.ui.** { *; }
-keep class com.kingzcheung.kime.plugin.funasr.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable