package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.IrisViolet
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.SlateMuted

/**
 * Real-time GPU-friendly voice waveform bar visualizer reacting smoothly to
 * microphone input volume, Nipa's synthesized speech amplitude, and current session state.
 */
@Composable
fun VoiceWaveformVisualizer(
    bands: List<Float>,
    sessionState: AssistantSessionState,
    modifier: Modifier = Modifier
) {
    val topBarColor by animateColorAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> SlateMuted.copy(alpha = 0.45f)
            AssistantSessionState.Connecting -> IrisViolet
            AssistantSessionState.Listening -> ElectricCyan
            AssistantSessionState.Thinking -> AmberThinking
            AssistantSessionState.Speaking -> MagentaPulse
            is AssistantSessionState.Error -> CoralAlert.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 250),
        label = "wave_top_color"
    )

    val bottomBarColor by animateColorAsState(
        targetValue = when (sessionState) {
            AssistantSessionState.Disconnected -> SlateMuted.copy(alpha = 0.2f)
            AssistantSessionState.Connecting -> ElectricCyan.copy(alpha = 0.65f)
            AssistantSessionState.Listening -> IrisViolet.copy(alpha = 0.85f)
            AssistantSessionState.Thinking -> IrisViolet.copy(alpha = 0.65f)
            AssistantSessionState.Speaking -> ElectricCyan.copy(alpha = 0.9f)
            is AssistantSessionState.Error -> CoralAlert.copy(alpha = 0.35f)
        },
        animationSpec = tween(durationMillis = 250),
        label = "wave_bottom_color"
    )

    val waveformLabel = stringResource(R.string.cd_waveform)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("voice_waveform")
            .semantics { contentDescription = waveformLabel }
    ) {
        if (bands.isEmpty()) return@Canvas

        val count = bands.size
        val spacing = (size.width * 0.014f).coerceAtLeast(3.dp.toPx())
        val totalSpacing = spacing * (count - 1)
        val barWidth = ((size.width - totalSpacing) / count).coerceIn(3.dp.toPx(), 8.dp.toPx())
        val contentWidth = barWidth * count + totalSpacing
        val startX = (size.width - contentWidth) / 2f
        val centerY = size.height / 2f

        val barBrush = Brush.verticalGradient(
            colors = listOf(topBarColor, bottomBarColor, topBarColor)
        )

        bands.forEachIndexed { index, normalized ->
            val clamped = normalized.coerceIn(0.06f, 1f)
            val barHeight = (size.height * clamped).coerceAtLeast(4.dp.toPx())
            val x = startX + index * (barWidth + spacing)
            val y = centerY - barHeight / 2f

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
