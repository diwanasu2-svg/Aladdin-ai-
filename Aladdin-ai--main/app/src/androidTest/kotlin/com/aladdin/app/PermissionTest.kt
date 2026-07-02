package com.aladdin.app

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.aladdin.app.permission.PermissionManager
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET,
        Manifest.permission.VIBRATE
    )

    @Test
    fun recordAudioPermissionGranted() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        assertThat(result).isEqualTo(android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    @Test
    fun internetPermissionGranted() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = ctx.checkSelfPermission(Manifest.permission.INTERNET)
        assertThat(result).isEqualTo(android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    @Test
    fun contextIsNotNull() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(ctx).isNotNull()
    }

    @Test
    fun packageNameIsCorrect() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(ctx.packageName).contains("aladdin")
    }
}
