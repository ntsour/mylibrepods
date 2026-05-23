/*
    ProPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 ProPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package io.nikos.propods

// import io.nikos.propods.screens.Onboarding
// import io.nikos.propods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import io.nikos.propods.data.AirPodsNotifications
import io.nikos.propods.data.ControlCommandRepository
import io.nikos.propods.presentation.components.AppInfoCard
import io.nikos.propods.presentation.components.ConfirmationDialog
import io.nikos.propods.presentation.components.DeviceInfoCard
import io.nikos.propods.presentation.components.SelectItem
import io.nikos.propods.presentation.components.StyledBottomSheet
import io.nikos.propods.presentation.components.StyledButton
import io.nikos.propods.presentation.components.StyledIconButton
import io.nikos.propods.presentation.components.StyledInputField
import io.nikos.propods.presentation.components.StyledSelectList
import io.nikos.propods.presentation.screens.AccessibilitySettingsScreen
import io.nikos.propods.presentation.screens.AdaptiveStrengthScreen
import io.nikos.propods.presentation.screens.AirPodsSettingsScreen
import io.nikos.propods.presentation.screens.AppSettingsScreen
import io.nikos.propods.presentation.screens.CameraControlScreen
import io.nikos.propods.presentation.screens.DebugScreen
import io.nikos.propods.presentation.screens.HeadTrackingScreen
import io.nikos.propods.presentation.screens.HearingAidAdjustmentsScreen
import io.nikos.propods.presentation.screens.HearingAidScreen
import io.nikos.propods.presentation.screens.HearingProtectionScreen
import io.nikos.propods.presentation.screens.LongPress
import io.nikos.propods.presentation.screens.OpenSourceLicensesScreen
import io.nikos.propods.presentation.screens.PurchaseScreen
import io.nikos.propods.presentation.screens.RenameScreen
import io.nikos.propods.presentation.screens.TransparencySettingsScreen
import io.nikos.propods.presentation.screens.TroubleshootingScreen
import io.nikos.propods.presentation.screens.UpdateHearingTestScreen

import io.nikos.propods.presentation.screens.AnnouncementAppPickerScreen
import io.nikos.propods.presentation.screens.AppPermissionsScreen
import io.nikos.propods.presentation.screens.NotificationAnnouncementsScreen
import io.nikos.propods.presentation.screens.ProximityFinderScreen
import io.nikos.propods.presentation.screens.VersionScreen
import io.nikos.propods.presentation.screens.CategoryScreen
import io.nikos.propods.presentation.screens.PressActionsScreen
import io.nikos.propods.presentation.screens.VolumeControlScreen
import io.nikos.propods.presentation.screens.CallControlsScreen
import io.nikos.propods.presentation.screens.ConversationAwarenessScreen
import io.nikos.propods.presentation.screens.BluetoothControlScreen
import io.nikos.propods.presentation.screens.AudioSettingsScreen
import io.nikos.propods.presentation.screens.ConnectionSettingsScreen
import io.nikos.propods.presentation.screens.MicrophoneSettingsScreen
import io.nikos.propods.presentation.screens.ListeningModeConfigScreen
import io.nikos.propods.presentation.screens.SmartAutomationScreen
import io.nikos.propods.presentation.screens.SleepTimerScreen
import io.nikos.propods.presentation.screens.PhoneBatteryScreen
import io.nikos.propods.presentation.screens.PopupAnimationsScreen
import io.nikos.propods.presentation.screens.XposedSettingsScreen
import io.nikos.propods.presentation.screens.EmailSupportScreen
import io.nikos.propods.presentation.screens.DiscordCommunityScreen
import io.nikos.propods.presentation.screens.GitHubIssuesScreen
import io.nikos.propods.presentation.screens.GymPressActionsScreen
import io.nikos.propods.presentation.screens.GymTimerScreen
import io.nikos.propods.presentation.theme.ProPodsTheme
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel
import io.nikos.propods.presentation.viewmodel.PurchaseViewModel
import io.nikos.propods.services.AirPodsService
import io.nikos.propods.services.CallNotifListener
import io.nikos.propods.utils.XposedState
import io.nikos.propods.utils.isAacpCapable
import kotlin.io.encoding.ExperimentalEncodingApi

lateinit var serviceConnection: ServiceConnection
lateinit var connectionStatusReceiver: BroadcastReceiver

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                System.loadLibrary("l2c_fcr_hook")
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ProPodsTheme {
                Main()
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }

    override fun onStop() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        super.onStop()
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main() {
    val context = LocalContext.current

    val isConnected = remember { mutableStateOf(false) }

    val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
    val isFirstLaunch = remember { !prefs.getBoolean("permissions_completed", false) }

    val airPodsService = remember { mutableStateOf<AirPodsService?>(null) }

    val airPodsViewModel = remember(airPodsService.value) {
        airPodsService.value?.let { service ->
            AirPodsViewModel(
                service = service,
                sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE),
                controlRepo = ControlCommandRepository(service.aacpManager),
                appContext = context.applicationContext
            )
        }
    }

    // XposedState.bluetoothScopeEnabled may flip false → true asynchronously after
    // the Xposed service binds — which can happen *after* the ViewModel was created.
    // Re-evaluate every time the activity resumes so AACP controls un-grey once
    // Xposed is actually online.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, airPodsViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                airPodsViewModel?.refreshAacpAvailable()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startDestination = if (isFirstLaunch) "permissions" else "settings"
    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
            val backButtonBackdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7))
                    .layerBackdrop(backButtonBackdrop)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    }) {
                    composable("settings") {
                        val appSettingsViewModel: AppSettingsViewModel = viewModel()
                        if (airPodsViewModel != null) AirPodsSettingsScreen(airPodsViewModel, appSettingsViewModel, navController)
                    }
                    composable("debug") {
                        DebugScreen(navController = navController)
                    }
                    composable("long_press/{bud}") { navBackStackEntry ->
                        if (airPodsViewModel != null) LongPress(
                            viewModel = airPodsViewModel,
                            name = navBackStackEntry.arguments?.getString("bud")!!,
                            navController = navController
                        )
                    }
                    composable("rename") {
                        if (airPodsViewModel != null) RenameScreen(airPodsViewModel)
                    }
                    composable("app_settings") {
                        // AppSettingsScreen content has been moved into the main screen menu.
                        // Route kept for backward-compatibility (deep links, etc.) but renders nothing.
                    }
                    composable("troubleshooting") {
                        TroubleshootingScreen(navController)
                    }
                    composable("head_tracking") {
                        if (airPodsViewModel != null) HeadTrackingScreen(airPodsViewModel, navController)
                    }
                    composable("accessibility") {
                        if (airPodsViewModel != null) AccessibilitySettingsScreen(airPodsViewModel, navController)
                    }
                    composable("transparency_customization") {
                        if (airPodsViewModel != null) TransparencySettingsScreen(airPodsViewModel)
                    }
                    composable("hearing_aid") {
                        if (airPodsViewModel != null) HearingAidScreen(airPodsViewModel, navController)
                    }
                    composable("hearing_aid_adjustments") {
                        if (airPodsViewModel != null) HearingAidAdjustmentsScreen(airPodsViewModel)
                    }
                    composable("adaptive_strength") {
                        if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                    }
                    composable("camera_control") {
                        if (airPodsViewModel != null) CameraControlScreen(airPodsViewModel)
                    }
                    composable("open_source_licenses") {
                        OpenSourceLicensesScreen(navController)
                    }
                    composable("update_hearing_test") {
                        if (airPodsViewModel != null) UpdateHearingTestScreen()
                    }
                    composable("version_info") {
                        if (airPodsViewModel != null) VersionScreen(airPodsViewModel)
                    }
                    composable("hearing_protection") {
                        if (airPodsViewModel != null) HearingProtectionScreen(airPodsViewModel, navController)
                    }
                    composable("purchase_screen") {
                        val purchaseViewModel: PurchaseViewModel = viewModel()
                        PurchaseScreen(purchaseViewModel, navController)
                    }
                    composable("permissions") {
                        val onGranted: (() -> Unit)? = if (isFirstLaunch) ({
                            prefs.edit().putBoolean("permissions_completed", true).apply()
                            navController.navigate("settings") {
                                popUpTo("permissions") { inclusive = true }
                            }
                        }) else null
                        AppPermissionsScreen(onPermissionsGranted = onGranted)
                    }
                    composable("notification_announcements") {
                        NotificationAnnouncementsScreen(navController)
                    }
                    composable("announcement_app_picker") {
                        AnnouncementAppPickerScreen(navController)
                    }
                    composable("proximity_finder") {
                        ProximityFinderScreen(navController = navController)
                    }
                    composable("smart_features") {
                        // Smart features are now inlined in the main menu.
                        // Route kept so any existing deep-link or back-stack reference doesn't crash.
                    }
                    composable(
                        route = "category/{key}?anchor={anchor}",
                        arguments = listOf(
                            navArgument("key") { type = NavType.StringType },
                            navArgument("anchor") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        val key = entry.arguments?.getString("key") ?: "controls"
                        val anchor = entry.arguments?.getString("anchor")
                        if (airPodsViewModel != null) CategoryScreen(
                            viewModel = airPodsViewModel,
                            appSettingsViewModel = viewModel(),
                            navController = navController,
                            categoryKey = key,
                            anchor = anchor,
                        )
                    }
                    composable("press_actions") {
                        if (airPodsViewModel != null) PressActionsScreen(airPodsViewModel)
                    }
                    composable("call_controls") {
                        if (airPodsViewModel != null) CallControlsScreen(airPodsViewModel)
                    }
                    composable("controls_configuration") {
                        if (airPodsViewModel != null) VolumeControlScreen(airPodsViewModel)
                    }
                    composable("conversation_awareness") {
                        if (airPodsViewModel != null) ConversationAwarenessScreen(airPodsViewModel, viewModel())
                    }
                    composable("bluetooth_control") {
                        if (airPodsViewModel != null) BluetoothControlScreen(airPodsViewModel)
                    }
                    composable("listening_mode_config") {
                        if (airPodsViewModel != null) ListeningModeConfigScreen(airPodsViewModel)
                    }
                    composable("adaptive_audio") {
                        if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                    }
                    composable("smart_automation") {
                        if (airPodsViewModel != null) SmartAutomationScreen(airPodsViewModel)
                    }
                    composable("sleep_timer") {
                        SleepTimerScreen()
                    }
                    composable("phone_battery") {
                        PhoneBatteryScreen(viewModel())
                    }
                    composable("popup_animations") {
                        PopupAnimationsScreen(viewModel())
                    }
                    composable("xposed_settings") {
                        XposedSettingsScreen(viewModel())
                    }
                    composable("audio_settings") {
                        if (airPodsViewModel != null) AudioSettingsScreen(airPodsViewModel, viewModel(), navController)
                    }
                    composable("connection_settings") {
                        if (airPodsViewModel != null) ConnectionSettingsScreen(airPodsViewModel)
                    }
                    composable("microphone_settings") {
                        if (airPodsViewModel != null) MicrophoneSettingsScreen(airPodsViewModel)
                    }
                    composable("email_support") {
                        EmailSupportScreen()
                    }
                    composable("discord_community") {
                        DiscordCommunityScreen()
                    }
                    composable("github_issues") {
                        GitHubIssuesScreen()
                    }
                    composable("gym_press_actions") {
                        if (airPodsViewModel != null) GymPressActionsScreen(airPodsViewModel)
                    }
                    composable("gym_timer") {
                        GymTimerScreen()
                    }
                }
            }

            val showBackButton = remember { mutableStateOf(false) }

            LaunchedEffect(navController) {
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    showBackButton.value =
                        destination.route != "settings" // && destination.route != "onboarding"
                }
            }

            AnimatedVisibility(
                visible = showBackButton.value,
                enter = fadeIn(animationSpec = tween()) + scaleIn(
                    initialScale = 0f,
                    animationSpec = tween()
                ),
                exit = fadeOut(animationSpec = tween()) + scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(100)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 8.dp, top = (LocalWindowInfo.current.containerSize.width * 0.05f).dp
                    )
            ) {
                StyledIconButton(
                    onClick = { navController.popBackStack() },
                    icon = "􀯶",
                    backdrop = backButtonBackdrop
                )
            }
        }

        context.startForegroundService(Intent(context, AirPodsService::class.java))

        serviceConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as AirPodsService.LocalBinder
                    airPodsService.value = binder.getService()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    airPodsService.value = null
                }
            }
        }

        context.bindService(
            Intent(context, AirPodsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (airPodsService.value?.isConnected() == true) {
            isConnected.value = true
        }
}

