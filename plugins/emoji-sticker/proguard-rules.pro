# Plugin ProGuard rules
-dontobfuscate
-optimizations !class/merging/*

-keep class kotlin.** { *; }
-keepnames class kotlin.** { *; }

-keep class com.kingzcheung.kime.plugin.emoji.** { *; }
-keepattributes SourceFile,LineNumberTable