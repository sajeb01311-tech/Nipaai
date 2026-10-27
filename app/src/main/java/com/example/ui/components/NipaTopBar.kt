package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.GlassSurfaceFill
import com.example.ui.theme.MistSilver
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.StarlightWhite

/**
 * Minimal futuristic top navigation bar:
 * Left: NIPA AI ASSISTANT + subtle live status pill
 * Right: Settings icon button
 */
@Composable
fun NipaTopBar(
    sessionState: AssistantSessionState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLive = sessionState == AssistantSessionState.Listening ||
        sessionState == AssistantSessionState.Speaking ||
        sessionState == AssistantSessionState.Thinking

    val dotColor by animateColorAsState(
        targetValue = when {
            isLive -> EmeraldOnline
            sessionState == AssistantSessionState.Connecting -> AmberThinking
            sessionState is AssistantSessionState.Error -> CoralAlert
            else -> SlateMuted
        },
        animationSpec = tween(durationMillis = 250),
        label = "status_dot_color"
    )

    val statusText = when {
        isLive -> stringResource(R.string.status_online)
        sessionState == AssistantSessionState.Connecting -> stringResource(R.string.status_connecting)
        sessionState is AssistantSessionState.Error -> stringResource(R.string.status_alert)
        else -> stringResource(R.string.status_standby)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.top_bar_title),
                style = MaterialTheme.typography.titleMedium,
                color = StarlightWhite
            )

            Spacer(modifier = Modifier.width(12.dp))

            // High-contrast status pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GlassSurfaceFill)
                    .border(1.dp, GlassBorderSubtle, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("connection_status_badge"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLive) EmeraldOnline else MistSilver
                )
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, GlassBorderSubtle, CircleShape)
                .testTag("settings_button")
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.cd_settings_button),
                tint = StarlightWhite
            )
        }
    }
}
