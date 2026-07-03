package com.aladdin.assistant.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.*
import androidx.navigation.compose.*
import com.aladdin.assistant.ui.screens.*
import com.aladdin.app.ui.settings.ProviderSettingsScreen
import com.aladdin.assistant.viewmodel.MainViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(id: String) = "chat/$id"
    }
    object Voice : Screen("voice")
    object Settings : Screen("settings")
    object Memory : Screen("memory")
    object ChatHistory : Screen("chat_history")
    object ProviderSettings : Screen("provider_settings")
}

@Composable
fun AladdinNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) +
            fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 2 }, animationSpec = tween(300)) +
            fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 2 }, animationSpec = tween(300)) +
            fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) +
            fadeOut(animationSpec = tween(300))
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToChat = { convId ->
                    viewModel.selectConversation(convId)
                    navController.navigate(Screen.Chat.createRoute(convId))
                },
                onNavigateToVoice = { navController.navigate(Screen.Voice.route) },
                onNavigateToHistory = { navController.navigate(Screen.ChatHistory.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToMemory = { navController.navigate(Screen.Memory.route) }
            )
        }
        composable(
            Screen.Chat.route,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val convId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            ChatScreen(
                conversationId = convId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Voice.route) {
            VoiceScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateToChat = { convId ->
                    navController.navigate(Screen.Chat.createRoute(convId)) {
                        popUpTo(Screen.Voice.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Memory.route) {
            MemoryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ChatHistory.route) {
            ChatHistoryScreen(
                viewModel = viewModel,
                onSelectConversation = { convId ->
                    viewModel.selectConversation(convId)
                    navController.navigate(Screen.Chat.createRoute(convId)) {
                        popUpTo(Screen.ChatHistory.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ProviderSettings.route) {
            ProviderSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
