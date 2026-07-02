package com.aladdin.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aladdin.app.download.ModelDownloaderHelper
import com.aladdin.app.permission.PermissionManager
import com.aladdin.app.service.AladdinForegroundService
import com.aladdin.app.ui.AladdinApp
import com.aladdin.app.ui.theme.AladdinTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var permissionManager: PermissionManager
    @Inject lateinit var modelDownloaderHelper: ModelDownloaderHelper

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty()) {
            onAllPermissionsGranted()
        } else {
            viewModel.setError("Permissions denied: ${denied.joinToString { it.substringAfterLast('.') }}")
        }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* battery result handled silently */ }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* overlay result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Task 27: Use Window API instead of deprecated showOnLockScreen manifest attribute.
        // setShowWhenLocked(true) + setTurnScreenOn(true) is the modern API (API 27+).
        // For API 26 (minSdk=26 in this project), use WindowManager.LayoutParams flags.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContent {
            AladdinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()
                    AladdinApp(
                        uiState = uiState,
                        onSendMessage = { viewModel.sendMessage(it) },
                        onStartListening = { viewModel.startListening() },
                        onStopListening = { viewModel.stopListening() },
                        onClearConversation = { viewModel.clearConversation() },
                        onStartService = { startAladdinService() }
                    )
                }
            }
        }

        requestAllPermissions()
        checkAndDownloadModels()
    }

    private fun requestAllPermissions() {
        val needed = permissionManager.getMissingPermissions(this)
        if (needed.isEmpty()) {
            onAllPermissionsGranted()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun onAllPermissionsGranted() {
        requestBatteryOptimizationExemption()
        requestOverlayPermissionIfNeeded()
        startAladdinService()
    }

    private fun checkAndDownloadModels() {
        lifecycleScope.launch {
            if (!modelDownloaderHelper.areAllModelsReady()) {
                modelDownloaderHelper.downloadModels(
                    onProgress = { name, percent ->
                        viewModel.setDownloadProgress(name, percent)
                    },
                    onComplete = {
                        viewModel.clearDownloadProgress()
                    },
                    onError = { error ->
                        viewModel.setError("Model download failed: $error")
                    }
                )
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                batteryOptLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) { /* some ROMs don't support this */ }
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun startAladdinService() {
        AladdinForegroundService.start(this)
    }
}
