# App ProGuard rules
-dontobfuscate

-optimizations !class/merging/*

# Keep ALL Kotlin stdlib (including internal classes like CollectionsKt__IterablesKt)
-keep class kotlin.** { *; }
-keepnames class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep class kotlin.collections.** { *; }
-keepnames class kotlin.collections.** { *; }
-keepclassmembers class kotlin.collections.** {
    public static *** *(...);
    public *** *(...);
}

-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

-keep class com.kingzcheung.kime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.kime.plugin.** { *; }

-keep class com.kingzcheung.kime.rime.** { *; }
-keep class com.kingzcheung.kime.**Jni** { *; }

-keepattributes SourceFile,LineNumberTable

-processkotlinnullchecks remove