# Architecture Migration Log

## Overview
This document tracks the unique files in `com.aladdin.assistant.*` that need to be migrated to `com.aladdin.app.*` as part of the duplicate architecture cleanup.

## Unique Files (To be Migrated)

The following files exist in `com.aladdin.assistant.*` and have no direct equivalent or need their specific logic migrated to `com.aladdin.app.*`:

- `com/aladdin/assistant/AladdinApplication.kt` (Needs merging with `com.aladdin.app.AladdinApp.kt`)
- `com/aladdin/assistant/bargein/BargeInManager.kt` (Check against `com.aladdin.app.conversation.BargeinHandler.kt`)
- `com/aladdin/assistant/data/model/ChatModels.kt`
- `com/aladdin/assistant/data/repository/ChatRepository.kt`
- `com/aladdin/assistant/di/AppModule.kt` (Needs merging with `com.aladdin.app.di.AppModule.kt`)
- `com/aladdin/assistant/di/OrchestratorModule.kt`
- `com/aladdin/assistant/llm/StreamingLLM.kt`
- `com/aladdin/assistant/noise/RNNoiseJniWrapper.kt`
- `com/aladdin/assistant/noise/RNNoise.kt`
- `com/aladdin/assistant/orchestrator/JarvisOrchestrator.kt` (Check against `JarvisService.kt`)
- `com/aladdin/assistant/receiver/BootReceiver.kt`
- `com/aladdin/assistant/service/AladdinForegroundService.kt`
- `com/aladdin/assistant/stt/StreamingSTT.kt`
- `com/aladdin/assistant/stt/WhisperEngine.kt`
- `com/aladdin/assistant/stt/WhisperJniWrapper.kt`
- `com/aladdin/assistant/tts/StreamingTTS.kt`
- `com/aladdin/assistant/ui/components/*` (ChatHistory.kt, NotificationUI.kt, VoiceAnimation.kt)
- `com/aladdin/assistant/ui/lockscreen/LockScreenControls.kt`
- `com/aladdin/assistant/ui/MainActivity.kt` (Needs merging with `com.aladdin.app.MainActivity.kt`)
- `com/aladdin/assistant/ui/navigation/NavHost.kt`
- `com/aladdin/assistant/ui/quicksettings/QuickSettingsTile.kt`
- `com/aladdin/assistant/ui/screens/*` (ChatHistoryScreen.kt, ChatScreen.kt, HomeScreen.kt, MemoryScreen.kt, SettingsScreen.kt, VoiceScreen.kt)
- `com/aladdin/assistant/ui/theme/Theme.kt`
- `com/aladdin/assistant/ui/widget/AssistantWidget.kt`
- `com/aladdin/assistant/vad/VADEngine.kt`
- `com/aladdin/assistant/viewmodel/MainViewModel.kt`
- `com/aladdin/assistant/wake/WakeWordEngine.kt`

## Deletion Candidates

Once the unique logic is safely migrated to the `com.aladdin.app.*` package, the entire `com/aladdin/assistant/` directory tree should be deleted to prevent further divergence.