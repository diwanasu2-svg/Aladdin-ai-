package com.aladdin.reliability.di

import android.content.Context
import com.aladdin.reliability.logging.LogConfig
import com.aladdin.reliability.manager.ReliabilityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReliabilityModule {

    @Provides
    @Singleton
    fun provideReliabilityManager(
        @ApplicationContext context: Context
    ): ReliabilityManager = ReliabilityManager(
        context              = context,
        appVersion           = getAppVersion(context),
        requiredPermissions  = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA
        ),
        logConfig = LogConfig(
            maxFiles      = 10,
            compress      = true,
            rotateDaily   = true,
            captureLogcat = true
        )
    )

    private fun getAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
