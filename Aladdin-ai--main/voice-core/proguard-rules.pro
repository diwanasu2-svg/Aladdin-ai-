# Voice Core ProGuard Rules

# Keep all VoiceCore public API
-keep class com.aladdin.voicecore.** { *; }

# Vosk
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# Porcupine
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# JNA (required by Vosk)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
