package com.aladdin.plugin.di

import android.content.Context
import com.aladdin.plugin.api.CoreServiceProvider
import com.aladdin.plugin.manager.PluginManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginManager(
        @ApplicationContext context: Context,
        coreServices: CoreServiceProvider
    ): PluginManager = PluginManager(context, coreServices)
}
