# Smart Memory ProGuard Rules

-keep class com.aladdin.memory.** { *; }
-keepclassmembers class com.aladdin.memory.db.entity.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-dontwarn dagger.hilt.**

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.**

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# WorkManager
-keep class androidx.work.** { *; }

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
