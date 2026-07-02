# Aladdin Tool System ProGuard Rules

-keep class com.aladdin.tools.** { *; }
-keepclassmembers class com.aladdin.tools.db.entity.** { *; }
-keepclassmembers class com.aladdin.tools.tools.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.**

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*

# Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Receivers / Services (must be kept for system to invoke them)
-keep class com.aladdin.tools.receiver.AlarmReceiver { *; }
-keep class com.aladdin.tools.service.TimerService { *; }
