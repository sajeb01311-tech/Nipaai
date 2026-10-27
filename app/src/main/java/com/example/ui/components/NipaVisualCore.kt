package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.IrisViolet
import com.example.ui.theme.MagentaPulse

/**
 * Central holographic Nipa visual character area (~70-80% of main screen vertical focus).
 * Features 250ms smooth opacity/color state transitions, idle breathing, listening sonar rings,
 * thinking orbital rotation, and speaking plasma glow modulation.
 */
@Composable
fun NipaVisualCore(
    sessionState: AssistantSessionState,
    inputLevel: Float,
    outputLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nipa_core_motion")

    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )

    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonar_pulse"
    )

    val primaryAuraColor by animateColorAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> CyanGlowSoft.copy(alpha = 0.25f)
            AssistantSessionState.Connecting -> IrisViolet.copy(alpha = 0.55f)
            AssistantSessionState.Listening -> ElectricCyan.copy(alpha = 0.72f)
            AssistantSessionState.Thinking -> AmberThinking.copy(alpha = 0.68f)
            AssistantSessionState.Speaking -> MagentaPulse.copy(alpha = 0.80f)
            is AssistantSessionState.Error -> CoralAlert.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = 260),
        label = "primary_aura_color"
    )

    val secondaryAuraColor by animateColorAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> IrisViolet.copy(alpha = 0.16f)
            AssistantSessionState.Connecting -> ElectricCyan.copy(alpha = 0.40f)
            AssistantSessionState.Listening -> IrisViolet.copy(alpha = 0.45f)
            AssistantSessionState.Thinking -> IrisViolet.copy(alpha = 0.58f)
            AssistantSessionState.Speaking -> ElectricCyan.copy(alpha = 0.75f)
            is AssistantSessionState.Error -> CoralAlert.copy(alpha = 0.28f)
        },
        animationSpec = tween(durationMillis = 260),
        label = "secondary_aura_color"
    )

    val targetCoreScale = when (sessionState) {
        AssistantSessionState.Disconnected -> idleBreath * 0.96f
        AssistantSessionState.Connecting -> idleBreath
        AssistantSessionState.Listening -> 1.0f + (inputLevel * 0.10f)
        AssistantSessionState.Thinking -> 0.98f + (idleBreath - 0.96f) * 0.6f
        AssistantSessionState.Speaking -> 1.02f + (outputLevel.coerceAtLeast(0.18f) * 0.13f)
        is AssistantSessionState.Error -> 0.95f
    }

    val smoothCoreScale by animateFloatAsState(
        targetValue = targetCoreScale,
        animationSpec = tween(durationMillis = 220),
        label = "core_scale"
    )

    val overlayAlpha by animateFloatAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> 0.58f
            AssistantSessionState.Connecting -> 0.78f
            AssistantSessionState.Listening -> 0.92f
            AssistantSessionState.Thinking -> 0.86f
            AssistantSessionState.Speaking -> 1.0f
            is AssistantSessionState.Error -> 0.62f
        },
        animationSpec = tween(durationMillis = 250),
        label = "core_alpha"
    )

    val coreDesc = stringResource(R.string.cd_nipa_core_visual)

    Box(
        modifier = modifier
            .sizeIn(maxWidth = 380.dp, maxHeight = 380.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = coreDesc },
        contentAlignment = Alignment.Center
    ) {
        // Outer luminous ambient halo & state-reactive orbital rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            // Deep radial luminous aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryAuraColor,
                        secondaryAuraColor,
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius * 0.98f
                ),
                radius = maxRadius * 0.98f,
                center = center
            )

            // Expanding sonar ring when Listening or Speaking
            if (sessionState == AssistantSessionState.Listening ||
                sessionState == AssistantSessionState.Speaking
            ) {
                val activeBoost = if (sessionState == AssistantSessionState.Speaking) {
                    outputLevel.coerceAtLeast(0.25f)
                } else {
                    inputLevel.coerceAtLeast(0.18f)
                }
                val ringRadius = maxRadius * (0.56f + pulsePhase * 0.38f)
                val ringAlpha = (1f - pulsePhase) * (0.35f + activeBoost * 0.45f)
                drawCircle(
                    color = primaryAuraColor.copy(alpha = ringAlpha.coerceIn(0f, 0.85f)),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 2.2.dp.toPx())
                )
            }

            // Precision futuristic orbital arcs
            val speedMultiplier = when (sessionState) {
                AssistantSessionState.Thinking,
                AssistantSessionState.Connecting -> 2.8f
                AssistantSessionState.Speaking -> 1.9f
                AssistantSessionState.Listening -> 1.4f
                else -> 0.6f
            }

            rotate(degrees = orbitAngle * speedMultiplier, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            primaryAuraColor.copy(alpha = 0.9f),
                            secondaryAuraColor.copy(alpha = 0.8f),
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = 15f,
                    sweepAngle = 135f,
                    useCenter = false,
                    topLeft = Offset(center.x - maxRadius * 0.76f, center.y - maxRadius * 0.76f),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 1.52f, maxRadius * 1.52f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            secondaryAuraColor.copy(alpha = 0.85f),
                            primaryAuraColor.copy(alpha = 0.7f),
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = 195f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(center.x - maxRadius * 0.76f, center.y - maxRadius * 0.76f),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 1.52f, maxRadius * 1.52f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Counter-rotating inner telemetry ring
            rotate(degrees = -orbitAngle * (speedMultiplier * 0.8f), pivot = center) {
                drawCircle(
                    color = primaryAuraColor.copy(alpha = 0.25f),
                    radius = maxRadius * 0.66f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        // Central Nipa Holographic Orb Asset with smooth scale & glassmorphic rim
        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .scale(smoothCoreScale)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryAuraColor.copy(alpha = 0.95f),
                            Color.White.copy(alpha = 0.25f),
                            secondaryAuraColor.copy(alpha = 0.9f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_nipa_core_1793069235118),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = overlayAlpha,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.18f)
            )

            // Subtle inner state tint glaze for seamless 250ms state transitions
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAuraColor.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.42f)
                        )
                    )
                )
            }
        }
    }
}
