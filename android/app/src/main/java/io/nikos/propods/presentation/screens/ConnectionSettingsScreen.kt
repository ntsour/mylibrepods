package io.nikos.propods.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.presentation.components.ConnectionSettings
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ConnectionSettingsScreen(viewModel: AirPodsViewModel, navController: NavController? = null) {
    val state by viewModel.uiState.collectAsState()

    StyledScaffold(title = "Connection Settings") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_connection_settings")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            ConnectionSettings(
                crossDeviceEnabled = state.crossDeviceEnabled,
                onCrossDeviceChanged = viewModel::setCrossDeviceEnabled,
                crossDevicePeers = state.crossDevicePeers,
                navController = navController,
                automaticEarDetectionEnabled = state.automaticEarDetectionEnabled,
                onAutomaticEarDetectionChanged = viewModel::setAutomaticEarDetectionEnabled,
                automaticConnectionEnabled = state.automaticConnectionEnabled,
                onAutomaticConnectionChanged = viewModel::setAutomaticConnectionEnabled
            )

            Spacer(Modifier.height(bottomPadding))
        }
    }
}
