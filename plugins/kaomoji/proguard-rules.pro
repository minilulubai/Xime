# Keep plugin entry class
-keep class com.kingzcheung.kime.plugin.kaomoji.KaomojiPlugin { *; }

# Keep all plugin classes
-keep class com.kingzcheung.kime.plugin.kaomoji.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable