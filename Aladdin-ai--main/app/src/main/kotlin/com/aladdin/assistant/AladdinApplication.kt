package com.aladdin.assistant

import android.app.Application
import com.aladdin.assistant.ui.components.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AladdinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }
}
