package com.aladdin.app.di

import android.content.Context
import com.aladdin.app.accessibility.AccessibilityHelper
import com.aladdin.app.backup.BackupManager
import com.aladdin.app.bluetooth.AladdinBluetoothManager
import com.aladdin.app.briefing.DailyBriefing
import com.aladdin.app.contacts.ContactManager
import com.aladdin.app.crash.CrashReporter
import com.aladdin.app.health.HealthMonitor
import com.aladdin.app.llm.*
import com.aladdin.app.location.AladdinLocationManager
import com.aladdin.app.mood.MoodDetector
import com.aladdin.app.network.NetworkMonitor
import com.aladdin.app.notification.AladdinNotificationManager
import com.aladdin.app.offline.OfflineFallback
import com.aladdin.app.performance.PerformanceMonitor
import com.aladdin.app.permission.PermissionManager
import com.aladdin.app.privacy.PrivacyManager
import com.aladdin.app.provider.ProviderManager
import com.aladdin.app.recommendation.RecommendationEngine
import com.aladdin.app.security.AuditLogger
import com.aladdin.app.security.SecureTokenManager
import com.aladdin.app.vision.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Core infrastructure ───────────────────────────────────────────────────

    @Provides @Singleton
    fun providePermissionManager(): PermissionManager = PermissionManager()

    /**
     * Task 1: Android Retrofit HTTPS security.
     * - Auth interceptor: attaches Bearer token from SecureTokenManager to every request.
     * - Certificate pinner: optional pinning for the backend domain.
     * - Logging only in DEBUG builds.
     */
    @Provides @Singleton
    fun provideOkHttpClient(
        @ApplicationContext ctx: Context,
        tokenManager: SecureTokenManager,
    ): OkHttpClient {
        // Task 1: JWT auth interceptor — attaches the stored access token to all API calls
        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val token = tokenManager.getAccessToken()
            val request = if (!token.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                originalRequest
            }
            val response = chain.proceed(request)

            // Task 1: Auto-refresh on 401 Unauthorized
            if (response.code == 401) {
                response.close()
                val refreshed = tokenManager.refreshAccessToken()
                if (refreshed != null) {
                    return@Interceptor chain.proceed(
                        originalRequest.newBuilder()
                            .header("Authorization", "Bearer $refreshed")
                            .build()
                    )
                }
            }
            response
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)  // Task 1: auth interceptor

        // Task 1: Certificate pinning (reads backend hostname + pins from BuildConfig or prefs)
        val backendHost = com.aladdin.app.BuildConfig::class.java
            .getField("BACKEND_HOST").get(null) as? String ?: ""
        val certPin = com.aladdin.app.BuildConfig::class.java
            .getField("BACKEND_CERT_PIN").get(null) as? String ?: ""
        if (backendHost.isNotBlank() && certPin.isNotBlank()) {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(backendHost, certPin)
                    .build()
            )
        }

        if (com.aladdin.app.BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }

        return builder.build()
    }

    @Provides @IoDispatcher      fun provideIoDispatcher():      CoroutineDispatcher = Dispatchers.IO
    @Provides @MainDispatcher    fun provideMainDispatcher():    CoroutineDispatcher = Dispatchers.Main
    @Provides @DefaultDispatcher fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    // ── Phase 9-10 providers ──────────────────────────────────────────────────

    @Provides @Singleton
    fun provideProviderManager(@ApplicationContext ctx: Context): ProviderManager = ProviderManager(ctx)

    @Provides @Singleton
    fun provideHealthMonitor(@ApplicationContext ctx: Context): HealthMonitor = HealthMonitor(ctx)

    @Provides @Singleton
    fun provideBackupManager(@ApplicationContext ctx: Context): BackupManager = BackupManager(ctx)

    @Provides @Singleton
    fun provideLlamaCppEngine(
        @ApplicationContext ctx: Context,
        quantSelector: QuantizedModelSelector,
    ): LlamaCppEngine = LlamaCppEngine(ctx, quantSelector)

    @Provides @Singleton
    fun provideMlcLlmEngine(@ApplicationContext ctx: Context): MlcLlmEngine = MlcLlmEngine(ctx)

    @Provides @Singleton
    fun provideQuantizedModelSelector(@ApplicationContext ctx: Context): QuantizedModelSelector = QuantizedModelSelector(ctx)

    @Provides @Singleton
    fun provideFallbackChain(providerManager: ProviderManager): FallbackChain = FallbackChain(providerManager)

    @Provides @Singleton
    fun provideResponseCache(@ApplicationContext ctx: Context): ResponseCache = ResponseCache(ctx)

    @Provides @Singleton
    fun provideConversationContextWindow(): ConversationContextWindow = ConversationContextWindow()

    @Provides @Singleton
    fun provideLLMRetryLogic(): LLMRetryLogic = LLMRetryLogic()

    @Provides @Singleton
    fun provideProviderBenchmark(pm: ProviderManager): ProviderBenchmark = ProviderBenchmark(pm)

    // ── Personalisation / AI features ─────────────────────────────────────────

    @Provides @Singleton
    fun provideMoodDetector(@ApplicationContext ctx: Context): MoodDetector = MoodDetector(ctx)

    @Provides @Singleton
    fun provideDailyBriefing(@ApplicationContext ctx: Context): DailyBriefing = DailyBriefing(ctx)

    @Provides @Singleton
    fun provideRecommendationEngine(@ApplicationContext ctx: Context): RecommendationEngine = RecommendationEngine(ctx)

    // ── System / connectivity ─────────────────────────────────────────────────

    @Provides @Singleton
    fun provideNetworkMonitor(@ApplicationContext ctx: Context): NetworkMonitor = NetworkMonitor(ctx)

    @Provides @Singleton
    fun provideOfflineFallback(@ApplicationContext ctx: Context): OfflineFallback = OfflineFallback(ctx)

    @Provides @Singleton
    fun provideNotificationManager(@ApplicationContext ctx: Context): AladdinNotificationManager = AladdinNotificationManager(ctx)

    @Provides @Singleton
    fun provideBluetoothManager(@ApplicationContext ctx: Context): AladdinBluetoothManager = AladdinBluetoothManager(ctx)

    @Provides @Singleton
    fun provideContactManager(@ApplicationContext ctx: Context): ContactManager = ContactManager(ctx)

    @Provides @Singleton
    fun provideLocationManager(@ApplicationContext ctx: Context): AladdinLocationManager = AladdinLocationManager(ctx)

    // ── Security / privacy / diagnostics ─────────────────────────────────────

    @Provides @Singleton
    fun provideAuditLogger(@ApplicationContext ctx: Context): AuditLogger = AuditLogger(ctx)

    @Provides @Singleton
    fun providePrivacyManager(@ApplicationContext ctx: Context): PrivacyManager = PrivacyManager(ctx)

    @Provides @Singleton
    fun providePerformanceMonitor(@ApplicationContext ctx: Context): PerformanceMonitor = PerformanceMonitor(ctx)

    @Provides @Singleton
    fun provideCrashReporter(@ApplicationContext ctx: Context): CrashReporter = CrashReporter(ctx)

    // ── Vision / perception ───────────────────────────────────────────────────

    @Provides @Singleton
    fun provideFaceRecognition(@ApplicationContext ctx: Context): FaceRecognition = FaceRecognition(ctx)

    @Provides @Singleton
    fun provideGestureRecognition(@ApplicationContext ctx: Context): GestureRecognition = GestureRecognition(ctx)

    @Provides @Singleton
    fun provideSceneUnderstanding(@ApplicationContext ctx: Context): SceneUnderstanding = SceneUnderstanding(ctx)

    @Provides @Singleton
    fun provideObjectMemory(@ApplicationContext ctx: Context): ObjectMemory = ObjectMemory(ctx)

    // ── UI / accessibility ────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideAccessibilityHelper(@ApplicationContext ctx: Context): AccessibilityHelper = AccessibilityHelper(ctx)
}
