package com.aladdin.tools.di

import android.content.Context
import com.aladdin.tools.db.ToolDatabase
import com.aladdin.tools.db.dao.*
import com.aladdin.tools.tools.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 9 + Phase 10 fix — ToolModule with ALL tools registered.
 *
 * Registers:
 *   Phase 9  — EmailTool, MapsTool, PhoneCallTool, SmsTool, WhatsAppTool,
 *               TelegramTool, DiscordTool, CameraTool, SmartHomeTool, ContactsTool
 *   Phase 10 — MouseControlTool, KeyboardTool, AppAutomationTool,
 *               ClipboardHistoryTool, NotificationControlTool,
 *               DeviceSettingsTool, AccessibilityAutomationTool
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    // ─── Database ─────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideToolDatabase(@ApplicationContext ctx: Context): ToolDatabase =
        ToolDatabase.getInstance(ctx)

    @Provides fun provideNoteDao(db: ToolDatabase): NoteDao = db.noteDao()
    @Provides fun provideTodoDao(db: ToolDatabase): TodoDao = db.todoDao()
    @Provides fun provideAlarmDao(db: ToolDatabase): AlarmDao = db.alarmDao()
    @Provides fun provideClipboardDao(db: ToolDatabase): ClipboardDao = db.clipboardDao()
    @Provides fun provideTimerDao(db: ToolDatabase): TimerDao = db.timerDao()

    // ─── Core Tools ──────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideCalculatorTool(): CalculatorTool = CalculatorTool()

    @Provides @Singleton
    fun provideWeatherTool(@ApplicationContext ctx: Context): WeatherTool = WeatherTool(ctx)

    @Provides @Singleton
    fun provideCalendarTool(@ApplicationContext ctx: Context): CalendarTool = CalendarTool(ctx)

    @Provides @Singleton
    fun provideAlarmTool(@ApplicationContext ctx: Context, dao: AlarmDao): AlarmTool = AlarmTool(ctx, dao)

    @Provides @Singleton
    fun provideTimerTool(@ApplicationContext ctx: Context, dao: TimerDao): TimerTool = TimerTool(ctx, dao)

    @Provides @Singleton
    fun provideNotesTool(dao: NoteDao): NotesTool = NotesTool(dao)

    @Provides @Singleton
    fun provideTodoTool(dao: TodoDao): TodoTool = TodoTool(dao)

    @Provides @Singleton
    fun provideFileReaderTool(@ApplicationContext ctx: Context): FileReaderTool = FileReaderTool(ctx)

    @Provides @Singleton
    fun providePdfReaderTool(@ApplicationContext ctx: Context): PdfReaderTool = PdfReaderTool(ctx)

    @Provides @Singleton
    fun provideClipboardTool(@ApplicationContext ctx: Context, dao: ClipboardDao): ClipboardTool =
        ClipboardTool(ctx, dao)

    @Provides @Singleton
    fun provideSystemInfoTool(@ApplicationContext ctx: Context): SystemInfoTool = SystemInfoTool(ctx)

    @Provides @Singleton
    fun provideAppLauncherTool(@ApplicationContext ctx: Context): AppLauncherTool = AppLauncherTool(ctx)

    @Provides @Singleton
    fun provideMusicControlTool(@ApplicationContext ctx: Context): MusicControlTool = MusicControlTool(ctx)

    @Provides @Singleton
    fun provideSafeShellTool(): SafeShellTool = SafeShellTool()

    // ─── Phase 9: Communication Tools ────────────────────────────────────────

    @Provides @Singleton
    fun provideEmailTool(@ApplicationContext ctx: Context): EmailTool = EmailTool(ctx)

    @Provides @Singleton
    fun provideMapsTool(@ApplicationContext ctx: Context): MapsTool = MapsTool(ctx)

    @Provides @Singleton
    fun providePhoneCallTool(@ApplicationContext ctx: Context): PhoneCallTool = PhoneCallTool(ctx)

    @Provides @Singleton
    fun provideSmsTool(@ApplicationContext ctx: Context): SmsTool = SmsTool(ctx)

    @Provides @Singleton
    fun provideWhatsAppTool(@ApplicationContext ctx: Context): WhatsAppTool = WhatsAppTool(ctx)

    @Provides @Singleton
    fun provideTelegramTool(@ApplicationContext ctx: Context): TelegramTool = TelegramTool(ctx)

    @Provides @Singleton
    fun provideDiscordTool(@ApplicationContext ctx: Context): DiscordTool = DiscordTool(ctx)

    @Provides @Singleton
    fun provideCameraTool(@ApplicationContext ctx: Context): CameraTool = CameraTool(ctx)

    @Provides @Singleton
    fun provideSmartHomeTool(@ApplicationContext ctx: Context): SmartHomeTool = SmartHomeTool(ctx)

    @Provides @Singleton
    fun provideContactsTool(@ApplicationContext ctx: Context): ContactsTool = ContactsTool(ctx)

    // ─── Phase 10: Computer Control Tools ────────────────────────────────────

    @Provides @Singleton
    fun provideMouseControlTool(@ApplicationContext ctx: Context): MouseControlTool = MouseControlTool(ctx)

    @Provides @Singleton
    fun provideKeyboardTool(@ApplicationContext ctx: Context): KeyboardTool = KeyboardTool(ctx)

    @Provides @Singleton
    fun provideAppAutomationTool(@ApplicationContext ctx: Context): AppAutomationTool = AppAutomationTool(ctx)

    @Provides @Singleton
    fun provideClipboardHistoryTool(@ApplicationContext ctx: Context): ClipboardHistoryTool =
        ClipboardHistoryTool(ctx)

    @Provides @Singleton
    fun provideNotificationControlTool(@ApplicationContext ctx: Context): NotificationControlTool =
        NotificationControlTool(ctx)

    @Provides @Singleton
    fun provideDeviceSettingsTool(@ApplicationContext ctx: Context): DeviceSettingsTool = DeviceSettingsTool(ctx)

    @Provides @Singleton
    fun provideAccessibilityAutomationTool(@ApplicationContext ctx: Context): AccessibilityAutomationTool =
        AccessibilityAutomationTool(ctx)
}
