package com.aladdin.app.di

import android.content.Context
import com.aladdin.app.audio.AudioRingBuffer
import com.aladdin.app.audio.BackgroundMicCapture
import com.aladdin.app.notification.JarvisMediaNotification
import com.aladdin.app.overlay.LockScreenOverlay
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * JarvisModule — provides Jarvis-specific singletons to the Hilt graph.
 *
 * Classes that use @Inject constructor and @Singleton don't need explicit
 * @Provides here — they are auto-discovered. This module provides the
 * classes that need constructor parameters Hilt can't infer (e.g. AudioRingBuffer
 * capacity, context-based constructors).
 */
@Module
@InstallIn(SingletonComponent::class)
object JarvisModule {

    /** 3-second ring buffer at 16 kHz = 48 000 short frames */
    @Provides @Singleton
    fun provideAudioRingBuffer(): AudioRingBuffer =
        AudioRingBuffer(capacityFrames = 48_000)

    @Provides @Singleton
    fun provideBackgroundMicCapture(ringBuffer: AudioRingBuffer): BackgroundMicCapture =
        BackgroundMicCapture(ringBuffer)

    @Provides @Singleton
    fun provideLockScreenOverlay(@ApplicationContext context: Context): LockScreenOverlay =
        LockScreenOverlay(context)

    @Provides @Singleton
    fun provideJarvisMediaNotification(@ApplicationContext context: Context): JarvisMediaNotification =
        JarvisMediaNotification(context)
}
