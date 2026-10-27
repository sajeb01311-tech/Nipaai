package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.IrisViolet
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.StarlightWhite

/**
 * Large central futuristic circular microphone/session button with soft glow,
 * press spring physics, listening pulse, speaking icon state, and accessible semantics.
 */
@Composable
fun FuturisticMicButton(
    sessionState: AssistantSessionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_halo_scale"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press_scale"
    )

    val accentColor by animateColorAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> ElectricCyan
            AssistantSessionState.Connecting -> IrisViolet
            AssistantSessionState.Listening -> ElectricCyan
            AssistantSessionState.Thinking -> AmberThinking
            AssistantSessionState.Speaking -> MagentaPulse
            is AssistantSessionState.Error -> CoralAlert
        },
        animationSpec = tween(durationMillis = 240),
        label = "mic_accent"
    )

    val isSessionActive = sessionState == AssistantSessionState.Listening ||
        sessionState == AssistantSessionState.Thinking ||
        sessionState == AssistantSessionState.Speaking ||
        sessionState == AssistantSessionState.Connecting

    val buttonContentDescription = if (isSessionActive) {
        stringResource(R.string.cd_mic_disconnect)
    } else {
        stringResource(R.string.cd_mic_connect)
    }

    Box(
        modifier = modifier
            .size(104.dp)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center
    ) {
        // Outer luminous pulse halo
        val activePulse = if (isSessionActive) pulseScale else 1.04f
        Box(
            modifier = Modifier
                .size(86.dp)
                .scale(activePulse)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (isSessionActive) 0.42f else 0.22f),
                                accentColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                }
        )

        // Core circular glassmorphic power/mic button
        Box(
            modifier = Modifier
                .size(78.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = if (isSessionActive) {
                            listOf(
                                accentColor.copy(alpha = 0.28f),
                                ObsidianElevated
                            )
                        } else {
                            listOf(
                                ElectricCyan,
                                IrisViolet
                            )
                        }
                    )
                )
                .border(
                    width = 1.8.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentColor,
                            StarlightWhite.copy(alpha = 0.45f),
                            accentColor.copy(alpha = 0.6f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = StarlightWhite),
                    role = Role.Button,
                    onClickLabel = buttonContentDescription,
                    onClick = onClick
                )
                .testTag("mic_power_button"),
            contentAlignment = Alignment.Center
        ) {
            when (sessionState) {
                AssistantSessionState.Connecting -> {
                    CircularProgressIndicator(
                        color = StarlightWhite,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                }
                AssistantSessionState.Speaking -> {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = buttonContentDescription,
                        tint = StarlightWhite,
                        modifier = Modifier.size(34.dp)
                    )
                }
                AssistantSessionState.Listening,
                AssistantSessionState.Thinking -> {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = buttonContentDescription,
                        tint = accentColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
                is AssistantSessionState.Error -> {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = buttonContentDescription,
                        tint = CoralAlert,
                        modifier = Modifier.size(32.dp)
                    )
                }
                AssistantSessionState.Disconnected -> {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = buttonContentDescription,
                        tint = ObsidianVoid,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}
