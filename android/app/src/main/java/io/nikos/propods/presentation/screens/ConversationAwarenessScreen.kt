package io.nikos.propods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.R
import io.nikos.propods.bluetooth.AACPManager
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledSlider
import io.nikos.propods.presentation.components.StyledToggle
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ConversationAwarenessScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    val convoEnabled = state.controlStates[
        AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
    ]?.getOrNull(0) == 0x01.toByte()
    val masterEnabled = appState.isPremium && state.aacpAvailable
    val subEnabled = convoEnabled && masterEnabled

    StyledScaffold(title = "Conversation Awareness") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_conversation_awareness")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                StyledToggle(
                    label = stringResource(R.string.conversational_awareness),
                    description = stringResource(R.string.conversational_awareness_master_description),
                    checked = convoEnabled && masterEnabled,
                    onCheckedChange = {
                        viewModel.setControlCommandBoolean(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it
                        )
                    },
                    independent = true,
                    enabled = masterEnabled,
                )
                Spacer(Modifier.height(12.dp))
                StyledToggle(label = stringResource(R.string.conversational_awareness_pause_music),
                    description = stringResource(R.string.conversational_awareness_pause_music_description),
                    checked = appState.conversationalAwarenessPauseMusicEnabled,
                    onCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled,
                    independent = true, enabled = subEnabled)
                Spacer(Modifier.height(12.dp))
                StyledToggle(label = stringResource(R.string.relative_conversational_awareness_volume),
                    description = stringResource(R.string.relative_conversational_awareness_volume_description),
                    checked = appState.relativeConversationalAwarenessVolumeEnabled,
                    onCheckedChange = appSettingsViewModel::setRelativeConversationalAwarenessVolumeEnabled,
                    independent = true, enabled = subEnabled)
                Spacer(Modifier.height(12.dp))
                StyledSlider(label = stringResource(R.string.conversational_awareness_volume),
                    description = stringResource(R.string.conversational_awareness_volume_description),
                    value = appState.conversationalAwarenessVolume, valueRange = 10f..85f,
                    snapPoints = listOf(44f), startLabel = "10%", endLabel = "85%",
                    onValueChange = { appSettingsViewModel.setConversationalAwarenessVolume(it) },
                    independent = true, enabled = subEnabled)
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}
