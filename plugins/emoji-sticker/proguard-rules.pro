# Keep plugin entry class
-keep class com.kingzcheung.kime.plugin.emoji.EmojiStickerPlugin { *; }

# Keep all plugin classes
-keep class com.kingzcheung.kime.plugin.emoji.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable