# AI Engine ProGuard Rules

-keep class com.aladdin.engine.** { *; }
-keepclassmembers class com.aladdin.engine.models.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-dontwarn dagger.hilt.**

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gemini SDK
-keep class com.google.ai.client.** { *; }
-dontwarn com.google.ai.client.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.aladdin.engine.**$$serializer { *; }
-keepclassmembers class com.aladdin.engine.** {
    *** Companion;
}

# Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Gson
-keep class com.google.gson.** { *; }
