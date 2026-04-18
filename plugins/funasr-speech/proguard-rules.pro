# Keep OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep plugin entry class
-keep class com.kingzcheung.kime.plugin.funasr.FunAsrPlugin { *; }

# Keep all plugin classes
-keep class com.kingzcheung.kime.plugin.funasr.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable