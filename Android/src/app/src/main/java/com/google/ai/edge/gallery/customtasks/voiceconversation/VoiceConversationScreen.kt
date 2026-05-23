package com.google.ai.edge.gallery.customtasks.voiceconversation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.voice.VoiceConversationUiState
import com.google.ai.edge.gallery.voice.VoiceState

@Composable
fun VoiceConversationScreen(
    task: Task,
    uiState: VoiceConversationUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val gradientColors = getTaskBgGradientColors(task = task)
    val scrollState = rememberScrollState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isListening = uiState.state == VoiceState.LISTENING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // State indicator
        Text(
            text = when (uiState.state) {
                VoiceState.IDLE -> "Tap to start listening"
                VoiceState.LISTENING -> "Listening..."
                VoiceState.THINKING -> "Thinking..."
                VoiceState.SPEAKING -> "Speaking..."
                VoiceState.ERROR -> "Error"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (uiState.state == VoiceState.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Main mic button
        Box(
            modifier = Modifier
                .size(120.dp)
                .then(
                    if (isListening) Modifier.scale(pulseScale) else Modifier
                )
                .clip(CircleShape)
                .background(
                    if (isListening) {
                        Brush.radialGradient(
                            colors = listOf(
                                gradientColors[0],
                                gradientColors.getOrElse(1) { gradientColors[0] }
                            )
                        )
                    } else {
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    when (uiState.state) {
                        VoiceState.IDLE -> onStartListening()
                        VoiceState.LISTENING -> onStopListening()
                        VoiceState.SPEAKING -> onStopListening()
                        VoiceState.THINKING -> onStopListening()
                        VoiceState.ERROR -> onStartListening()
                    }
                },
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                ),
            ) {
                Icon(
                    imageVector = when (uiState.state) {
                        VoiceState.IDLE -> Icons.Outlined.Mic
                        VoiceState.LISTENING -> Icons.Outlined.Stop
                        VoiceState.THINKING -> Icons.Outlined.Stop
                        VoiceState.SPEAKING -> Icons.Outlined.Stop
                        VoiceState.ERROR -> Icons.Outlined.Mic
                    },
                    contentDescription = if (isListening) "Stop" else "Start",
                    modifier = Modifier.size(40.dp),
                    tint = if (isListening) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Audio level bar (simple visual)
        if (uiState.audioLevel > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((uiState.audioLevel / 32768f).coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(gradientColors[0])
                )
            }
        }

        // Mute button during speaking
        if (uiState.state == VoiceState.SPEAKING) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onToggleMute,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMuted)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    imageVector = if (uiState.isMuted) Icons.Outlined.MicOff else Icons.Outlined.VolumeUp,
                    contentDescription = "Toggle mute"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // User text display
        if (uiState.userText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "You said:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.userText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Response text display
        if (uiState.responseText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "AI Response:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.responseText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Thinking indicator
        if (uiState.state == VoiceState.THINKING) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = gradientColors[0]
            )
        }

        // Error message
        if (uiState.errorMessage != null && uiState.state == VoiceState.ERROR) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = uiState.errorMessage,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
