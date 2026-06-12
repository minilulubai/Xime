# App ProGuard rules
-dontobfuscate

-optimizations !class/merging/*

# Keep Kotlin stdlib classes used by plugins via parent classloader
# Plugins use compileOnly(plugin-core), so Kotlin stdlib resolves from host app.
# R8 strips unused stdlib methods — these rules ensure plugins can call them.
-keep class kotlin.** { *; }

-keep class com.kingzcheung.xime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.xime.plugin.** { *; }

-keep class com.kingzcheung.xime.rime.** { *; }
-keep class com.kingzcheung.xime.**Jni** { *; }

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    <fields>;
    <methods>;
}

-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

-processkotlinnullchecks remove