# Keep MediaPipe / LiteRT-LM classes — they have JNI bindings that R8 must not strip.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# Kotlin metadata
-keep class kotlin.Metadata { *; }
