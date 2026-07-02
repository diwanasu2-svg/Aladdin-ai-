# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# Keep Gemini AI client
-keep class com.google.ai.client.generativeai.** { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep Vision module public API
-keep class com.aladdin.vision.** { *; }
-keepclassmembers class com.aladdin.vision.** { *; }
