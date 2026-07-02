package com.aladdin.app.messaging.di

import com.aladdin.app.messaging.discord.DiscordApi
import com.aladdin.app.messaging.email.GmailApi
import com.aladdin.app.messaging.telegram.TelegramApi
import com.aladdin.app.messaging.whatsapp.WhatsAppApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * MessagingModule — provides Retrofit instances for each platform API.
 *
 * Each API has its own Retrofit instance because they use different base URLs.
 * All share a common [OkHttpClient] with logging + timeouts.
 */
@Module
@InstallIn(SingletonComponent::class)
object MessagingModule {

    // ─── Shared OkHttpClient ──────────────────────────────────────────────────

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ─── Telegram ─────────────────────────────────────────────────────────────
    // Base URL uses a placeholder token; TelegramBot injects a token-aware URL at runtime.
    // We use a generic endpoint host and the bot constructs the full path.

    @Provides @Singleton @Named("telegram")
    fun provideTelegramRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.telegram.org/bot_placeholder/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideTelegramApi(@Named("telegram") retrofit: Retrofit): TelegramApi =
        retrofit.create(TelegramApi::class.java)

    // ─── WhatsApp ─────────────────────────────────────────────────────────────

    @Provides @Singleton @Named("whatsapp")
    fun provideWhatsAppRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://graph.facebook.com/v19.0/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideWhatsAppApi(@Named("whatsapp") retrofit: Retrofit): WhatsAppApi =
        retrofit.create(WhatsAppApi::class.java)

    // ─── Discord ──────────────────────────────────────────────────────────────

    @Provides @Singleton @Named("discord")
    fun provideDiscordRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://discord.com/api/v10/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideDiscordApi(@Named("discord") retrofit: Retrofit): DiscordApi =
        retrofit.create(DiscordApi::class.java)

    // ─── Gmail ────────────────────────────────────────────────────────────────

    @Provides @Singleton @Named("gmail")
    fun provideGmailRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://gmail.googleapis.com/gmail/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideGmailApi(@Named("gmail") retrofit: Retrofit): GmailApi =
        retrofit.create(GmailApi::class.java)
}
