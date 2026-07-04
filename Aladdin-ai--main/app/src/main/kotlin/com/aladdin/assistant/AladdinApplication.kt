package com.aladdin.assistant

import android.app.Application
import com.aladdin.assistant.ui.components.AppNotificationChannels

// NOTE: This legacy Application class is not referenced by AndroidManifest.xml
// (the active Application is com.aladdin.app.AladdinApp, namespace "com.aladdin.app").
// It must NOT carry @HiltAndroidApp, since Hilt only supports a single
// @HiltAndroidApp-annotated Application per module; having two caused
// `:app:hiltAggregateDepsDebug` to fail.
class AladdinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.createAll(this)
    }
}
