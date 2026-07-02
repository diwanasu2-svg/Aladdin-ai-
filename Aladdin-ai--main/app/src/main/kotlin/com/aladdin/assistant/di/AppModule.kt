package com.aladdin.assistant.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.aladdin.assistant.data.repository.AladdinDatabase
import com.aladdin.assistant.data.repository.ConversationDao
import com.aladdin.assistant.data.repository.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aladdin_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AladdinDatabase =
        Room.databaseBuilder(context, AladdinDatabase::class.java, "aladdin_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: AladdinDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AladdinDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore
}
