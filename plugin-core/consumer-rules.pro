# Plugin Core Consumer Rules
# These rules ensure all plugin API interfaces are preserved

# Keep all API interfaces and implementations
-keep class com.kingzcheung.kime.plugin.core.api.** { *; }
-keep interface com.kingzcheung.kime.plugin.core.api.** { *; }

# Keep all model classes
-keep class com.kingzcheung.kime.plugin.core.model.** { *; }

# Keep all runtime classes
-keep class com.kingzcheung.kime.plugin.core.runtime.** { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keep @kotlin.Metadata class * { <methods>; }

# Keep suspend function signatures
-keepclassmembers class * {
    public *** *(kotlin.coroutines.Continuation);
}