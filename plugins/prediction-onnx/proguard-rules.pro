# Keep plugin entry class
-keep class com.kingzcheung.kime.plugin.prediction.OnnxPredictionPlugin { *; }

# Keep all plugin classes  
-keep class com.kingzcheung.kime.plugin.prediction.** { *; }

# Keep ONNX Runtime native methods
-keep class ai.onnxruntime.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable