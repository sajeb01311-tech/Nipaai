package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.domain.AudioDiagnostics
import com.example.domain.NipaVoiceOption
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.IrisViolet
import com.example.ui.theme.MistSilver
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.StarlightWhite

/**
 * Slide-in futuristic Settings & Telemetry drawer displaying:
 * - Assistant: Nipa personality, Voice profile & selector, Connection status
 * - Audio: Microphone permission, Speaker status, Input stream, Output stream
 * - About: Nipa AI Assistant, Real-time voice AI, Powered by Gemini Live
 */
@Composable
fun NipaSettingsDrawerOverlay(
    isOpen: Boolean,
    sessionState: AssistantSessionState,
    selectedVoice: NipaVoiceOption,
    diagnostics: AudioDiagnostics,
    onSelectVoice: (NipaVoiceOption) -> Unit,
    onClose: () -> Unit
) {
    if (isOpen) {
        BackHandler(onBack = onClose)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(260)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(220)
            ),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            NipaSettingsPanelContent(
                sessionState = sessionState,
                selectedVoice = selectedVoice,
                diagnostics = diagnostics,
                onSelectVoice = onSelectVoice,
                onClose = onClose,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 300.dp, max = 380.dp)
                    .fillMaxWidth(0.88f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NipaSettingsPanelContent(
    sessionState: AssistantSessionState,
    selectedVoice: NipaVoiceOption,
    diagnostics: AudioDiagnostics,
    onSelectVoice: (NipaVoiceOption) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val connectionLabel = when (sessionState) {
        AssistantSessionState.Disconnected -> stringResource(R.string.status_standby)
        AssistantSessionState.Connecting -> stringResource(R.string.state_connecting)
        AssistantSessionState.Listening -> stringResource(R.string.state_listening)
        AssistantSessionState.Thinking -> stringResource(R.string.state_thinking)
        AssistantSessionState.Speaking -> stringResource(R.string.state_speaking)
        is AssistantSessionState.Error -> stringResource(R.string.status_alert)
    }

    val connectionAccent = when (sessionState) {
        AssistantSessionState.Listening,
        AssistantSessionState.Speaking -> EmeraldOnline
        AssistantSessionState.Connecting,
        AssistantSessionState.Thinking -> AmberThinking
        is AssistantSessionState.Error -> CoralAlert
        AssistantSessionState.Disconnected -> SlateMuted
    }

    Column(
        modifier = modifier
            .background(ObsidianSurface.copy(alpha = 0.96f))
            .border(width = 1.dp, color = GlassBorderSubtle, shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("settings_panel")
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = StarlightWhite
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MistSilver
                )
            }

            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ObsidianElevated)
                        .border(1.dp, GlassBorderSubtle, CircleShape)
                        .testTag("close_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close_settings),
                        tint = StarlightWhite
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 1: ASSISTANT
        SectionHeader(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.section_assistant)
        )
        Spacer(modifier = Modifier.height(10.dp))

        GlassDiagnosticCard {
            DiagnosticRow(
                label = stringResource(R.string.label_personality),
                value = stringResource(R.string.value_personality),
                accentColor = ElectricCyan
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            DiagnosticRow(
                label = stringResource(R.string.label_voice_status),
                value = "${selectedVoice.displayName} · ${selectedVoice.tagline}",
                accentColor = IrisViolet
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            DiagnosticRow(
                label = stringResource(R.string.label_connection_status),
                value = connectionLabel,
                accentColor = connectionAccent,
                showIndicatorDot = true
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.label_voice_selector),
                style = MaterialTheme.typography.labelMedium,
                color = MistSilver
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NipaVoiceOption.entries.forEach { option ->
                    val selected = option == selectedVoice
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectVoice(option) },
                        label = {
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.18f),
                            selectedLabelColor = ElectricCyan,
                            containerColor = ObsidianSurface,
                            labelColor = MistSilver
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = Color.White.copy(alpha = 0.12f),
                            selectedBorderColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("voice_chip_${option.voiceId.lowercase()}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 2: AUDIO
        SectionHeader(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.section_audio)
        )
        Spacer(modifier = Modifier.height(10.dp))

        GlassDiagnosticCard {
            DiagnosticRow(
                label = stringResource(R.string.label_mic_permission),
                value = if (diagnostics.micPermissionGranted) {
                    stringResource(R.string.status_granted)
                } else {
                    stringResource(R.string.status_not_granted)
                },
                accentColor = if (diagnostics.micPermissionGranted) EmeraldOnline else CoralAlert,
                leadingIcon = Icons.Outlined.Mic,
                showIndicatorDot = true
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            DiagnosticRow(
                label = stringResource(R.string.label_speaker_status),
                value = if (diagnostics.isOutputPlaying) {
                    stringResource(R.string.status_speaker_streaming)
                } else if (sessionState == AssistantSessionState.Listening || sessionState == AssistantSessionState.Thinking) {
                    stringResource(R.string.status_speaker_ready)
                } else {
                    stringResource(R.string.status_speaker_standby)
                },
                accentColor = if (diagnostics.isOutputPlaying) ElectricCyan else MistSilver,
                leadingIcon = Icons.Outlined.VolumeUp
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            DiagnosticRow(
                label = stringResource(R.string.label_input_status),
                value = if (diagnostics.isInputCapturing) {
                    stringResource(R.string.status_input_capturing)
                } else {
                    stringResource(R.string.status_input_idle)
                },
                accentColor = if (diagnostics.isInputCapturing) EmeraldOnline else SlateMuted,
                showIndicatorDot = true
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            DiagnosticRow(
                label = stringResource(R.string.label_output_status),
                value = if (diagnostics.isOutputPlaying) {
                    stringResource(R.string.status_output_playing)
                } else {
                    stringResource(R.string.status_output_idle)
                },
                accentColor = if (diagnostics.isOutputPlaying) IrisViolet else SlateMuted,
                showIndicatorDot = true
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 3: ABOUT
        SectionHeader(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.section_about)
        )
        Spacer(modifier = Modifier.height(10.dp))

        GlassDiagnosticCard {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleMedium,
                color = StarlightWhite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ElectricCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.about_powered_by),
                style = MaterialTheme.typography.labelMedium,
                color = MistSilver
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(EmeraldOnline.copy(alpha = 0.12f))
                    .border(1.dp, EmeraldOnline.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = EmeraldOnline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.about_tools_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldOnline
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = ElectricCyan
        )
    }
}

@Composable
private fun GlassDiagnosticCard(
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianElevated.copy(alpha = 0.78f))
            .border(1.dp, GlassBorderSubtle, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    accentColor: Color,
    leadingIcon: ImageVector? = null,
    showIndicatorDot: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MistSilver,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MistSilver
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showIndicatorDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor
            )
        }
    }
}
