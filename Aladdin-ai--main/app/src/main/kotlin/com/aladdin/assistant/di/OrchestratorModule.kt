package com.aladdin.assistant.di

import android.content.Context
import com.aladdin.assistant.orchestrator.JarvisOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 1 fix item 1.5 — Hilt module for assistant.orchestrator.JarvisOrchestrator.
 *
 * JarvisOrchestrator is annotated @Singleton + @Inject constructor, so Hilt
 * auto-discovers it. This module is retained for explicit documentation and
 * future configuration overrides (e.g. test doubles).
 */
@Module
@InstallIn(SingletonComponent::class)
object OrchestratorModule {

    /**
     * Explicitly provides JarvisOrchestrator even though Hilt can auto-discover it.
     * Keeping this explicit makes it easy to swap the implementation in tests.
     */
    @Provides
    @Singleton
    fun provideJarvisOrchestrator(@ApplicationContext context: Context): JarvisOrchestrator =
        JarvisOrchestrator(context)
}
