package com.aladdin.assistant.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import com.aladdin.assistant.data.model.VoiceState
import kotlin.math.*

// ─── Voice Animation Orb ──────────────────────────────────────────────────────
@Composable
fun VoiceAnimationOrb(
    voiceState: VoiceState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.15f + amplitude * 0.3f
            VoiceState.SPEAKING -> 1.1f
            VoiceState.PROCESSING -> 1.05f
            VoiceState.WAKE_WORD_DETECTED -> 1.2f
            else -> 1.02f
        },
        animationSpec = infiniteRepeatable(
            tween(when (voiceState) {
                VoiceState.LISTENING -> 600
                VoiceState.SPEAKING -> 800
                VoiceState.PROCESSING -> 1000
                else -> 2000
            }),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = voiceState.rotationDuration(), easing = LinearEasing)),
        label = "rotation"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (voiceState != VoiceState.IDLE) 0.7f else 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow"
    )

    val orbColor = when (voiceState) {
        VoiceState.IDLE -> MaterialTheme.colorScheme.primary
        VoiceState.LISTENING -> Color(0xFF2196F3)
        VoiceState.PROCESSING -> Color(0xFFFF9800)
        VoiceState.SPEAKING -> Color(0xFF9C27B0)
        VoiceState.WAKE_WORD_DETECTED -> Color(0xFF4CAF50)
        VoiceState.ERROR -> Color(0xFFF44336)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Glow rings
        repeat(4) { i ->
            val ringScale = 1f + (i + 1) * 0.25f
            Surface(
                modifier = Modifier
                    .size(140.dp)
                    .scale(ringScale * pulseScale)
                    .alpha(glowAlpha / (i + 1).toFloat()),
                shape = CircleShape,
                color = orbColor.copy(alpha = 0f),
                border = androidx.compose.foundation.BorderStroke(
                    width = (3 - i).dp.coerceAtLeast(0.5.dp),
                    color = orbColor.copy(alpha = glowAlpha / (i + 1))
                )
            ) {}
        }

        // Rotating gradient ring (only when active)
        if (voiceState != VoiceState.IDLE) {
            Canvas(modifier = Modifier.size(160.dp).rotate(rotationAngle)) {
                val sweepGradient = Brush.sweepGradient(
                    listOf(orbColor.copy(0f), orbColor.copy(0.8f), orbColor.copy(0f))
                )
                drawCircle(
                    brush = sweepGradient,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    radius = size.minDimension / 2f
                )
            }
        }

        // Main orb
        Surface(
            modifier = Modifier.size(120.dp).scale(pulseScale),
            shape = CircleShape,
            color = orbColor,
            shadowElevation = if (voiceState != VoiceState.IDLE) 16.dp else 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Inner icon
                AnimatedContent(
                    targetState = voiceState,
                    transitionSpec = { (fadeIn() + scaleIn()) togetherWith (fadeOut() + scaleOut()) },
                    label = "icon"
                ) { state ->
                    Icon(
                        imageVector = when (state) {
                            VoiceState.IDLE -> Icons.Default.AutoAwesome
                            VoiceState.LISTENING -> Icons.Default.Mic
                            VoiceState.PROCESSING -> Icons.Default.Psychology
                            VoiceState.SPEAKING -> Icons.Default.RecordVoiceOver
                            VoiceState.WAKE_WORD_DETECTED -> Icons.Default.Hearing
                            VoiceState.ERROR -> Icons.Default.Error
                        },
                        contentDescription = state.name,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
        }
    }
}

// ─── Waveform Visualizer ──────────────────────────────────────────────────────
@Composable
fun WaveformVisualizer(
    voiceState: VoiceState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(when (voiceState) {
                VoiceState.LISTENING -> 800
                VoiceState.SPEAKING -> 600
                else -> 2000
            }, easing = LinearEasing)
        ),
        label = "phase"
    )

    val barCount = 40
    val color = when (voiceState) {
        VoiceState.IDLE -> MaterialTheme.colorScheme.onBackground.copy(0.15f)
        VoiceState.LISTENING -> Color(0xFF2196F3)
        VoiceState.PROCESSING -> Color(0xFFFF9800)
        VoiceState.SPEAKING -> Color(0xFF9C27B0)
        VoiceState.WAKE_WORD_DETECTED -> Color(0xFF4CAF50)
        VoiceState.ERROR -> Color(0xFFF44336)
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2f)
        val maxBarHeight = size.height * 0.9f
        val baseHeight = size.height * 0.05f
        val cx = size.width / 2f
        val cy = size.height / 2f

        for (i in 0 until barCount) {
            val x = i * 2 * barWidth + barWidth / 2
            val normalizedPos = (i.toFloat() / barCount) * 2 * PI.toFloat()

            val heightFactor = when (voiceState) {
                VoiceState.IDLE -> 0.08f + 0.04f * sin(normalizedPos * 3 + phase).absoluteValue
                VoiceState.LISTENING -> 0.1f + (0.5f + amplitude * 0.5f) * abs(sin(normalizedPos * 2 + phase))
                VoiceState.SPEAKING -> 0.15f + 0.6f * abs(sin(normalizedPos * 3 + phase * 1.5f))
                VoiceState.PROCESSING -> 0.1f + 0.4f * abs(sin(normalizedPos * 4 + phase * 0.7f))
                else -> 0.08f + 0.3f * abs(sin(normalizedPos * 2 + phase))
            }

            val barHeight = (maxBarHeight * heightFactor).coerceAtLeast(baseHeight)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, cy - barHeight / 2),
                size = Size(barWidth * 0.7f, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

private fun VoiceState.rotationDuration(): Int = when (this) {
    VoiceState.IDLE -> 8000
    VoiceState.LISTENING -> 3000
    VoiceState.PROCESSING -> 1500
    VoiceState.SPEAKING -> 4000
    else -> 5000
}
