package com.aladdin.security.di

import android.content.Context
import com.aladdin.security.config.SecureConfigManager
import com.aladdin.security.dependency.DependencyVerifier
import com.aladdin.security.manager.SecurityManager
import com.aladdin.security.secrets.SecretManager
import com.aladdin.security.secrets.SecretRotation
import com.aladdin.security.storage.SecureFileStorage
import com.aladdin.security.storage.SecurePreferences
import com.aladdin.security.subprocess.SafeSubprocess
import com.aladdin.security.validation.InputValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides @Singleton
    fun provideSecurityManager(@ApplicationContext context: Context) =
        SecurityManager(context).also { it.start() }

    @Provides @Singleton
    fun provideSecretManager(manager: SecurityManager) = manager.secrets

    @Provides @Singleton
    fun provideSecretRotation(manager: SecurityManager) = manager.rotation

    @Provides @Singleton
    fun provideInputValidator(manager: SecurityManager) = manager.validator

    @Provides @Singleton
    fun provideSafeSubprocess(manager: SecurityManager) = manager.subprocess

    @Provides @Singleton
    fun provideSecureConfigManager(manager: SecurityManager) = manager.config

    @Provides @Singleton
    fun provideSecureFileStorage(manager: SecurityManager) = manager.fileStorage

    @Provides @Singleton
    fun provideSecurePreferences(manager: SecurityManager) = manager.preferences

    @Provides @Singleton
    fun provideDependencyVerifier(manager: SecurityManager) = manager.depVerifier
}
