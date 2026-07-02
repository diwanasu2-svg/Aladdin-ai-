package com.aladdin.performance.di

import android.content.Context
import com.aladdin.performance.async.AsyncProcessor
import com.aladdin.performance.cache.EmbeddingModelCache
import com.aladdin.performance.cache.PiperModelCache
import com.aladdin.performance.cache.WhisperModelCache
import com.aladdin.performance.manager.PerformanceOptimizationManager
import com.aladdin.performance.memory.LruCacheManager
import com.aladdin.performance.memory.MemoryOptimizer
import com.aladdin.performance.pipeline.PipelineOptimizer
import com.aladdin.performance.startup.StartupOptimizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PerformanceOptimizationModule {

    @Provides @Singleton
    fun provideManager(@ApplicationContext context: Context) =
        PerformanceOptimizationManager(context)

    @Provides @Singleton
    fun provideWhisperCache(manager: PerformanceOptimizationManager) = manager.whisperCache

    @Provides @Singleton
    fun providePiperCache(manager: PerformanceOptimizationManager) = manager.piperCache

    @Provides @Singleton
    fun provideEmbeddingCache(manager: PerformanceOptimizationManager) = manager.embeddingCache

    @Provides @Singleton
    fun provideLruCaches(manager: PerformanceOptimizationManager) = manager.lruCaches

    @Provides @Singleton
    fun provideAsyncProcessor(manager: PerformanceOptimizationManager) = manager.asyncProcessor

    @Provides @Singleton
    fun providePipeline(manager: PerformanceOptimizationManager) = manager.pipeline

    @Provides @Singleton
    fun provideStartupOptimizer(manager: PerformanceOptimizationManager) = manager.startupOptimizer

    @Provides @Singleton
    fun provideMemoryOptimizer(manager: PerformanceOptimizationManager) = manager.memoryOptimizer
}
