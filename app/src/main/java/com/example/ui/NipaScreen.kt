package com.example.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.domain.AssistantSessionState
import com.example.domain.BrowserActionEvent
import com.example.domain.NipaUiState
import com.example.ui.components.FuturisticMicButton
import com.example.ui.components.NipaSettingsDrawerOverlay
import com.example.ui.components.NipaSettingsPanelContent
import com.example.ui.components.NipaTopBar
import com.example.ui.components.NipaVisualCore
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.GlassSurfaceFill
import com.example.ui.theme.IrisViolet
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.MistSilver
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.StarlightWhite
import kotlinx.coroutines.delay

/**
 * Voice-first futuristic AI Command Center screen for Nipa AI Assistant.
 * No text chat bubbles or history — pure real-time audio-to-audio experience
 * with adaptive canonical Supporting Pane layout on wide tablets/foldables.
 */
@Composable
fun NipaScreen(
    viewModel: NipaViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

    // Refresh permission telemetry when returning from system Settings or browser
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-dismiss transient safe browser action toast pill after 4 seconds
    LaunchedEffect(uiState.lastBrowserAction) {
        if (uiState.lastBrowserAction != null) {
            delay(4_000L)
            viewModel.clearBrowserActionBanner()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .drawBehind {
                // Atmospheric deep space radial luminescence
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            IrisViolet.copy(alpha = 0.14f),
                            ElectricCyan.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.34f),
                        radius = size.maxDimension * 0.62f
                    ),
                    radius = size.maxDimension * 0.62f,
                    center = Offset(size.width * 0.5f, size.height * 0.34f)
                )
            }
            .testTag("nipa_command_screen")
    ) {
        val isExpandedLayout = maxWidth >= 840.dp

        if (isExpandedLayout) {
            // Canonical Supporting Pane layout for tablets & unfolded devices
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    NipaTopBar(
                        sessionState = uiState.sessionState,
                        onOpenSettings = { viewModel.setSettingsOpen(!uiState.isSettingsOpen) }
                    )

                    MainVoiceStage(
                        uiState = uiState,
                        onMicTap = {
                            viewModel.onMicButtonTapped {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onRetry = {
                            if (uiState.audioDiagnostics.micPermissionGranted) {
                                viewModel.connectSession()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenAppSettings = {
                            val settingsIntent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(settingsIntent)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Permanent supporting telemetry & voice settings pane on Expanded displays
                NipaSettingsPanelContent(
                    sessionState = uiState.sessionState,
                    selectedVoice = uiState.selectedVoice,
                    diagnostics = uiState.audioDiagnostics,
                    onSelectVoice = viewModel::selectVoice,
                    onClose = null,
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                )
            }
        } else {
            // Mobile-First Full-Screen Command Center
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    NipaTopBar(
                        sessionState = uiState.sessionState,
                        onOpenSettings = { viewModel.setSettingsOpen(true) }
                    )

                    MainVoiceStage(
                        uiState = uiState,
                        onMicTap = {
                            viewModel.onMicButtonTapped {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onRetry = {
                            if (uiState.audioDiagnostics.micPermissionGranted) {
                                viewModel.connectSession()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onOpenAppSettings = {
                            val settingsIntent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(settingsIntent)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Slide-in glassmorphic settings drawer
                NipaSettingsDrawerOverlay(
                    isOpen = uiState.isSettingsOpen,
                    sessionState = uiState.sessionState,
                    selectedVoice = uiState.selectedVoice,
                    diagnostics = uiState.audioDiagnostics,
                    onSelectVoice = viewModel::selectVoice,
                    onClose = { viewModel.setSettingsOpen(false) }
                )
            }
        }
    }
}

@Composable
private fun MainVoiceStage(
    uiState: NipaUiState,
    onMicTap: () -> Unit,
    onRetry: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Transient browser tool confirmation badge (if a browser action just executed)
        BrowserActionPill(event = uiState.lastBrowserAction)

        // NIPA VISUAL (occupies ~70% of central vertical focus)
        Box(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            NipaVisualCore(
                sessionState = uiState.sessionState,
                inputLevel = uiState.audioDiagnostics.inputRmsLevel,
                outputLevel = uiState.audioDiagnostics.outputRmsLevel,
                modifier = Modifier.fillMaxSize(0.94f)
            )
        }

        // WAVEFORM + SESSION STATUS + MIC CONTROL DOCK
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Real-time Waveform
            VoiceWaveformVisualizer(
                bands = uiState.waveformBands,
                sessionState = uiState.sessionState,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Primary Real-Time Session State Label
            SessionStatusDisplay(
                sessionState = uiState.sessionState,
                showPermissionRationale = uiState.showPermissionRationale,
                onRetry = onRetry,
                onOpenAppSettings = onOpenAppSettings
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Central Futuristic Microphone Button
            FuturisticMicButton(
                sessionState = uiState.sessionState,
                onClick = onMicTap
            )
        }
    }
}

@Composable
private fun SessionStatusDisplay(
    sessionState: AssistantSessionState,
    showPermissionRationale: Boolean,
    onRetry: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val primaryLabel = when (sessionState) {
        AssistantSessionState.Disconnected -> stringResource(R.string.state_disconnected)
        AssistantSessionState.Connecting -> stringResource(R.string.state_connecting)
        AssistantSessionState.Listening -> stringResource(R.string.state_listening)
        AssistantSessionState.Thinking -> stringResource(R.string.state_thinking)
        AssistantSessionState.Speaking -> stringResource(R.string.state_speaking)
        is AssistantSessionState.Error -> sessionState.message
    }

    val subLabel = when (sessionState) {
        AssistantSessionState.Disconnected -> stringResource(R.string.sublabel_disconnected)
        AssistantSessionState.Connecting -> stringResource(R.string.sublabel_connecting)
        AssistantSessionState.Listening -> stringResource(R.string.sublabel_listening)
        AssistantSessionState.Thinking -> stringResource(R.string.sublabel_thinking)
        AssistantSessionState.Speaking -> stringResource(R.string.sublabel_speaking)
        is AssistantSessionState.Error -> stringResource(R.string.sublabel_error)
    }

    val headlineColor = when (sessionState) {
        AssistantSessionState.Disconnected -> StarlightWhite
        AssistantSessionState.Connecting -> IrisViolet
        AssistantSessionState.Listening -> ElectricCyan
        AssistantSessionState.Thinking -> AmberThinking
        AssistantSessionState.Speaking -> MagentaPulse
        is AssistantSessionState.Error -> CoralAlert
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_state_container")
    ) {
        AnimatedContent(
            targetState = primaryLabel,
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            },
            label = "session_primary_text"
        ) { targetText ->
            Text(
                text = targetText,
                style = if (sessionState is AssistantSessionState.Error) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = headlineColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("session_primary_label")
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MistSilver,
            textAlign = TextAlign.Center
        )

        // Friendly recovery actions on error or denied microphone permission
        if (sessionState is AssistantSessionState.Error || showPermissionRationale) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ElectricCyan.copy(alpha = 0.16f),
                        contentColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("retry_session_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showPermissionRationale) {
                            stringResource(R.string.action_grant_mic)
                        } else {
                            stringResource(R.string.action_retry)
                        }
                    )
                }

                if (showPermissionRationale) {
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MistSilver),
                        modifier = Modifier.testTag("open_app_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.action_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserActionPill(event: BrowserActionEvent?) {
    AnimatedVisibility(
        visible = event != null,
        enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { -it / 2 })
    ) {
        if (event != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GlassSurfaceFill)
                    .border(1.dp, EmeraldOnline.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("browser_action_pill"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = EmeraldOnline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.browser_toast_opening, event.actionTitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = StarlightWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
