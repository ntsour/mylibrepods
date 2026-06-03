/*
    ProPods - AirPods liberated from Apple's ecosystem
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

package io.nikos.propods.presentation.screens

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.nikos.propods.BuildConfig
import io.nikos.propods.R
import io.nikos.propods.bluetooth.AACPManager
import io.nikos.propods.bluetooth.ATTHandles
import io.nikos.propods.data.AirPodsPro3
import io.nikos.propods.data.Capability
import io.nikos.propods.data.StemAction
import io.nikos.propods.presentation.components.AboutCard
import io.nikos.propods.presentation.components.AppInfoCard
import io.nikos.propods.presentation.components.AudioSettings
import io.nikos.propods.presentation.components.BatteryView
import io.nikos.propods.presentation.components.CallControlSettings
import io.nikos.propods.presentation.components.ConnectionSettings

import io.nikos.propods.presentation.components.DeviceInfoCard
import io.nikos.propods.presentation.components.MicrophoneSettings
import io.nikos.propods.presentation.components.NavigationButton
import io.nikos.propods.presentation.components.NoiseControlSettings
import io.nikos.propods.presentation.components.SelectItem
import io.nikos.propods.presentation.components.StyledBottomSheet
import io.nikos.propods.presentation.components.StyledButton
import io.nikos.propods.presentation.components.StyledIconButton
import io.nikos.propods.presentation.components.StyledInputField
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledSelectList
import io.nikos.propods.presentation.components.StyledSlider
import io.nikos.propods.presentation.components.StyledToggle
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel
import io.nikos.propods.services.AppListenerService
import io.nikos.propods.utils.SleepTimer
import io.nikos.propods.utils.SmartFeaturesPrefs
import io.nikos.propods.utils.XposedState
import kotlin.io.encoding.ExperimentalEncodingApi

// ─── Design tokens ───────────────────────────────────────────────────────────
internal val RootOrange          = Color(0xFFFF9500)
internal const val DisabledAlpha = 0.45f
internal val SfPro get()         = FontFamily(Font(R.font.sf_pro))

// ─── Searchable menu item ─────────────────────────────────────────────────────
data class SearchableItem(
    val label: String,
    val category: String,
    val categoryKey: String,
    val directRoute: String,
    val anchor: String? = null,
    val keywords: List<String> = emptyList(),
)

val searchIndex: List<SearchableItem> by lazy { buildSearchIndex() }

private fun buildSearchIndex(): List<SearchableItem> = listOf(
    // AirPods Controls
    SearchableItem("Left Bud — Single Press",  "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Left Bud — Double Press",  "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Left Bud — Triple Press",  "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Left Bud — Long Press",    "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "hold", "press and hold")),
    SearchableItem("Right Bud — Single Press", "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Right Bud — Double Press", "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Right Bud — Triple Press", "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "tap", "click")),
    SearchableItem("Right Bud — Long Press",   "AirPods Controls", "controls", "press_actions", keywords = listOf("stem", "hold", "press and hold")),
    SearchableItem("Listening Mode Configuration", "AirPods Controls", "controls", "listening_mode_config", keywords = listOf("anc", "noise cancellation", "transparency", "adaptive", "off")),
    SearchableItem("Call Controls",            "AirPods Controls", "controls", "call_controls", keywords = listOf("answer", "end call", "mute")),
    SearchableItem("Volume Control",           "AirPods Controls", "controls", "controls_configuration", keywords = listOf("swipe", "touch")),
    SearchableItem("Controls Configuration",   "AirPods Controls", "controls", "controls_configuration", keywords = listOf("press speed", "hold duration", "tone")),
    SearchableItem("Gym Mode",                 "Smart Features", "smart", "category/smart", anchor = "gym_mode", keywords = listOf("workout", "fitness", "stem", "timer")),
    SearchableItem("Gym Press Actions",        "AirPods Controls", "controls", "gym_press_actions", keywords = listOf("workout", "fitness", "stem")),
    // AirPods Settings
    SearchableItem("Customize Transparency Mode", "AirPods Settings", "settings", "transparency_customization", keywords = listOf("transparency", "customize", "anc", "adaptive")),
    SearchableItem("Device Name",              "AirPods Settings", "settings", "rename", keywords = listOf("rename", "bluetooth name")),
    SearchableItem("Hearing Aid",              "AirPods Settings", "settings", "hearing_aid", keywords = listOf("amplification", "audiogram")),
    SearchableItem("Hearing Protection",       "AirPods Settings", "settings", "hearing_protection", keywords = listOf("ppe", "loud sound")),
    SearchableItem("Loud Sound Reduction",     "AirPods Settings", "settings", "hearing_protection", keywords = listOf("volume limit", "ppe")),
    SearchableItem("Conversation Awareness",   "AirPods Settings", "settings", "conversation_awareness", keywords = listOf("speak", "talk", "auto pause", "auto volume")),
    SearchableItem("Disconnect",               "AirPods Settings", "settings", "bluetooth_control", keywords = listOf("bluetooth", "root", "turn off")),
    // Smart Features
    SearchableItem("Notification Announcements", "Smart Features", "smart", "notification_announcements", keywords = listOf("tts", "read", "speak", "siri")),
    SearchableItem("Find my Case",             "Smart Features", "smart", "proximity_finder", keywords = listOf("locate", "nearby", "radar")),
    SearchableItem("Head Gestures",            "Smart Features", "smart", "head_tracking", keywords = listOf("nod", "shake", "answer call")),
    SearchableItem("Adaptive Audio",           "Smart Features", "smart", "adaptive_audio", keywords = listOf("anc strength", "noise control")),
    SearchableItem("Camera Control",           "Smart Features", "smart", "camera_control", keywords = listOf("shutter", "photo")),
    SearchableItem("Sleep Detection",          "Smart Features", "smart", "smart_automation", keywords = listOf("auto sleep", "bedtime")),
    SearchableItem("Optimized Charging",       "Smart Features", "smart", "smart_automation", keywords = listOf("battery health", "slow charge")),
    SearchableItem("Resume Media After Call",  "Smart Features", "smart", "smart_automation", keywords = listOf("auto resume", "playback")),
    SearchableItem("Battery Alerts",           "Smart Features", "smart", "smart_automation", keywords = listOf("low battery", "speak", "announce")),
    SearchableItem("Sleep Timer",              "Smart Features", "smart", "sleep_timer", keywords = listOf("auto stop", "timer")),
    SearchableItem("Gym Timer",                "Smart Features", "smart", "gym_timer", keywords = listOf("stopwatch", "workout", "fitness")),
    // App Settings
    SearchableItem("Battery in Widget",        "App Settings", "appsettings", "phone_battery", keywords = listOf("widget")),
    SearchableItem("Pop-up Animations",        "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "connect")),
    SearchableItem("Bottom Sheet Popup",       "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "connect")),
    SearchableItem("Dynamic Island Popup",     "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "island", "connect")),
    SearchableItem("Act as Apple Device",      "App Settings", "appsettings", "xposed_settings", keywords = listOf("xposed", "vendor id", "apple", "hook")),
    SearchableItem("Permissions",              "App Settings", "appsettings", "permissions", keywords = listOf("access", "grant")),
    SearchableItem("Background Activity",      "App Settings", "appsettings", "category/appsettings", keywords = listOf("battery", "optimization", "unrestricted", "background")),
    SearchableItem("Connect When Disconnected","App Settings", "appsettings", "app_settings", keywords = listOf("takeover", "auto connect", "airpods disconnected")),
    SearchableItem("Connect When Idle",        "App Settings", "appsettings", "app_settings", keywords = listOf("takeover", "auto connect", "idle")),
    SearchableItem("Connect When Playing Media","App Settings", "appsettings", "app_settings", keywords = listOf("takeover", "auto connect", "music", "media")),
    SearchableItem("Connect When On Call",     "App Settings", "appsettings", "app_settings", keywords = listOf("takeover", "auto connect", "call")),
    SearchableItem("Connect When Receiving a Call","App Settings","appsettings","app_settings", keywords = listOf("takeover", "auto connect", "ringing", "incoming call")),
    SearchableItem("Connect When Starting Media","App Settings","appsettings","app_settings", keywords = listOf("takeover", "auto connect", "media starts", "playback")),
    // Audio & Connection
    SearchableItem("Audio Settings",           "Audio & Connection", "audio", "audio_settings", keywords = listOf("eq", "equalizer", "adaptive volume")),
    SearchableItem("Personalized Volume",      "Audio & Connection", "audio", "audio_settings", keywords = listOf("adaptive volume", "volume adjust", "environment")),
    SearchableItem("Handover to Other Devices","Audio & Connection", "audio", "connection_settings", keywords = listOf("cross device", "auto switch", "handover", "other device")),
    SearchableItem("Peer Android Device",      "Audio & Connection", "audio", "connection_settings", keywords = listOf("peer", "other device", "android", "handover", "cross device")),
    SearchableItem("Ear Detection",            "Audio & Connection", "audio", "connection_settings", keywords = listOf("auto pause", "in ear", "wear detection")),
    SearchableItem("Disconnect When Not Wearing","Audio & Connection","audio", "connection_settings", keywords = listOf("auto disconnect", "wear detection")),
    SearchableItem("Microphone Settings",      "Audio & Connection", "audio", "microphone_settings", keywords = listOf("mic", "microphone mode")),
    SearchableItem("Connection Settings",      "Audio & Connection", "audio", "connection_settings", keywords = listOf("auto connect", "bluetooth")),
    // Help
    SearchableItem("Troubleshooting",          "Help & Troubleshooting", "help", "troubleshooting", keywords = listOf("fix", "debug", "problem")),
    SearchableItem("Open Source Licenses",     "Help & Troubleshooting", "help", "open_source_licenses", keywords = listOf("license", "credits")),
    SearchableItem("Version Info",             "Help & Troubleshooting", "help", "version_info", keywords = listOf("about", "app version")),
    SearchableItem("Email Support",            "Help & Troubleshooting", "help", "email_support", keywords = listOf("contact")),
    SearchableItem("Discord Community",        "Help & Troubleshooting", "help", "discord_community", keywords = listOf("chat")),
    SearchableItem("GitHub Issues",            "Help & Troubleshooting", "help", "github_issues", keywords = listOf("bug", "report")),
)

@Composable internal fun bodyStyle(dark: Boolean) = TextStyle(
    fontSize = 16.sp, fontFamily = SfPro,
    color = if (dark) Color.White else Color.Black
)
@Composable internal fun captionStyle(dark: Boolean) = TextStyle(
    fontSize = 13.sp, fontFamily = SfPro,
    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
)

// ─── Shared primitives ───────────────────────────────────────────────────────

@Composable
internal fun MenuSectionHeader(label: String, dark: Boolean, requiresAacp: Boolean = false) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (dark) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label.uppercase(), style = TextStyle(
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
        ))
        if (requiresAacp) {
            io.nikos.propods.presentation.components.RequiresAacpIcon()
        }
    }
}

@Composable
internal fun MenuDivider() = HorizontalDivider(
    color = Color(0x30888888), thickness = 0.5.dp,
    modifier = Modifier.padding(horizontal = 16.dp)
)

@Composable
internal fun MenuNavRow(
    label: String,
    dark: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    val rowModifier = Modifier.fillMaxWidth()
        .then(
            if (enabled) Modifier.clickable(
                remember { MutableInteractionSource() }, null, onClick = onClick
            ) else Modifier
        )
        .padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 14.dp)
        .alpha(rowAlpha)
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = bodyStyle(dark))
            if (subtitle != null)
                Text(subtitle, style = captionStyle(dark))
        }
        if (!enabled) {
            io.nikos.propods.presentation.components.RequiresAacpIcon()
            Spacer(Modifier.width(8.dp))
        }
        Text("  ›", style = TextStyle(fontSize = 20.sp, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)))
    }
}

internal val XposedOrange = Color(0xFFFF9500)

@Composable
internal fun XposedRequiredBanner(dark: Boolean) = Row(
    Modifier.fillMaxWidth()
        .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
) {
    Text("⚠", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = XposedOrange))
    Text(
        "Requires the Xposed module with \"Act as Apple device\" enabled in App Settings.",
        style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = XposedOrange)
    )
}

@Composable
internal fun RootRequiredBanner(dark: Boolean) = Row(
    Modifier.fillMaxWidth()
        .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
) {
    Text("􀎠", style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = RootOrange))
    Text("Requires device root. Bluetooth profile switching is not available without it.",
        style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = RootOrange))
}

@Composable
private fun MenuCategory(
    label: String,
    dark: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    val borderColor = if (highlighted) Color(0xFF0A84FF) else Color.Transparent
    Column(Modifier.fillMaxWidth()
        .background(if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF), RoundedCornerShape(18.dp))
        .then(if (highlighted) Modifier.border(2.dp, borderColor, RoundedCornerShape(18.dp)) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { onToggle() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
            Text(if (expanded) "  ▲" else "  ▼", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.4f)))
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(200))) {
            Column {
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                content()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ENTRY POINT
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController
) {
    val state    by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark     = isSystemInDarkTheme()
    val context  = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    val contactBottomSheet = remember { mutableStateOf(false) }
    val subjectState       = remember { TextFieldState() }
    val descriptionState   = remember { TextFieldState() }
    val backdrop           = rememberLayerBackdrop()

    var deviceName by remember {
        mutableStateOf(TextFieldValue(state.deviceName))
    }
    LaunchedEffect(state.deviceName) {
        if (deviceName.text.isBlank() || deviceName.text == "AirPods" || deviceName.text == "AirPods Pro") {
            deviceName = TextFieldValue(state.deviceName)
        }
    }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "name") deviceName = TextFieldValue(prefs.getString("name", state.deviceName) ?: state.deviceName)
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(l)
        onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refreshInitialData() }

    // Search state
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery  by rememberSaveable { mutableStateOf("") }
    val searchIndex  = remember { buildSearchIndex() }
    val searchResults = remember(searchQuery) {
        if (searchQuery.isEmpty()) emptyList()
        else {
            val q = searchQuery.trim().lowercase()
            searchIndex.filter { item ->
                item.label.contains(q, ignoreCase = true) ||
                    item.category.contains(q, ignoreCase = true) ||
                    item.keywords.any { it.contains(q, ignoreCase = true) }
            }
        }
    }

    val searchFocusRequester = remember { FocusRequester() }

    // Auto-focus the search field when search becomes active
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Small delay to let the composition settle before requesting focus
            delay(100)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    StyledScaffold(
        title = if (searchActive) "" else deviceName.text,
        titleAlign = androidx.compose.ui.text.style.TextAlign.Start,
        actionButtons = if (state.isLocallyConnected || (!state.aacpAvailable && state.isA2dpConnected)) listOf({ scaffoldBackdrop ->
            if (searchActive) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.weight(1f)
                            .testTag("search_input")
                            .focusRequester(searchFocusRequester)
                            .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) Text("Search settings...", style = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.35f)))
                            innerTextField()
                        }
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    StyledIconButton(onClick = { searchActive = false; searchQuery = "" }, icon = "􀆄", backdrop = scaffoldBackdrop)
                }
            } else {
                StyledIconButton(onClick = { searchActive = true }, icon = "􀊫", backdrop = scaffoldBackdrop, modifier = Modifier.testTag("nav_settings_search_button"))
            }
        }) else emptyList(),
        snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->
        var blockTouches by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect { blockTouches = true; delay(1000); blockTouches = false }
        }

        // Search results overlay
        if (searchActive && searchResults.isNotEmpty()) {
            val cardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            LazyColumn(
                Modifier.fillMaxWidth().fillMaxHeight()
                    .padding(top = topPadding + 4.dp)
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                        searchResults.forEachIndexed { idx, item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(remember { MutableInteractionSource() }, null) {
                                        searchActive = false; searchQuery = ""
                                        val target = if (item.anchor != null)
                                            "${item.directRoute}?anchor=${item.anchor}"
                                        else item.directRoute
                                        navController.navigate(target)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(item.label, style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                                    Text(item.category, style = TextStyle(fontSize = 12.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.45f)))
                                }
                                Text("›", style = TextStyle(fontSize = 18.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.3f) else Color.Black.copy(0.3f)))
                            }
                            if (idx < searchResults.size - 1)
                                HorizontalDivider(color = Color(0x25888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        } else if (state.isLocallyConnected || (!state.aacpAvailable && state.isA2dpConnected)) {
            // Limited-mode devices get ConnectedScreen only when A2DP is up.
            // AACP-only controls inside ConnectedScreen are greyed out via state.aacpAvailable.
            ConnectedScreen(
                state = state, appState = appState,
                viewModel = viewModel, appSettingsViewModel = appSettingsViewModel,
                navController = navController, sharedPrefs = sharedPreferences,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark, blockTouches = blockTouches,
                onOpenContact = { contactBottomSheet.value = true },
            )
        } else {
            DisconnectedScreen(
                state = state, viewModel = viewModel,
                appSettingsViewModel = appSettingsViewModel,
                navController = navController,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark,
                onOpenContact = { contactBottomSheet.value = true }
            )
        }
    }

    // Contact bottom sheet — shared between connected + disconnected modes
    StyledBottomSheet(visible = contactBottomSheet.value, onDismiss = { contactBottomSheet.value = false }, backdrop = backdrop) { innerBackdrop, progress ->
        val animPad = lerp(16.dp, 2.dp, progress)
        Column(Modifier.fillMaxWidth().padding(horizontal = animPad).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StyledIconButton(icon = "\uDBC0\uDD84", backdrop = innerBackdrop, onClick = { contactBottomSheet.value = false })
                Text(stringResource(R.string.describe_your_issue), style = TextStyle(fontSize = 18.sp, fontFamily = SfPro, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (dark) Color.White else Color.Black))
                StyledIconButton(
                    icon = "\uDBC0\uDE1F", backdrop = innerBackdrop,
                    surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF),
                    iconTint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                    enabled = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                    onClick = {
                        contactBottomSheet.value = false
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "mailto:".toUri()
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("contact@kavish.xyz"))
                            putExtra(Intent.EXTRA_SUBJECT, "ProPods: ${subjectState.text}")
                            putExtra(Intent.EXTRA_TEXT,
                                "${descriptionState.text}\n\n----------" +
                                "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                "\nDISPLAY: ${Build.DISPLAY}" +
                                "\nSDK: ${Build.VERSION.SDK_INT_FULL}" +
                                "\nXposed: ${XposedState.isAvailable}/${XposedState.bluetoothScopeEnabled}" +
                                "\nVERSION: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                                "\nFLAVOR: ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}")
                        }
                        context.startActivity(intent)
                        subjectState.clearText(); descriptionState.clearText()
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            StyledInputField(inputState = subjectState, focusRequester = remember { FocusRequester() }, placeholder = stringResource(R.string.subject))
            Spacer(Modifier.height(12.dp))
            StyledInputField(inputState = descriptionState, focusRequester = remember { FocusRequester() }, placeholder = stringResource(R.string.describe_your_issue), singleLine = false)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  CONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ConnectedScreen(
    state: io.nikos.propods.presentation.viewmodel.AirPodsUiState,
    appState: io.nikos.propods.presentation.viewmodel.AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    blockTouches: Boolean,
    onOpenContact: () -> Unit,
) {
    val context      = LocalContext.current
    val capabilities = state.capabilities

    // Weight-based layout: top content takes natural height, tile grid fills the rest.
    // This guarantees all 6 tiles are always visible without scrolling.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .padding(top = topPadding, bottom = bottomPadding)
            .then(if (blockTouches) Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) { val e = awaitPointerEvent(PointerEventPass.Initial); e.changes.forEach { it.consume() } } }
            } else Modifier),
    ) {
        // ── Audio-route status ─────────────────────────────────────────────────
        // Limited-mode devices always render ConnectedScreen even when the audio
        // isn't routed here — so show an explicit indicator. AACP-capable
        // devices show "Connected" when AACP is up; otherwise show A2DP or "on
        // another device" / "not nearby".
        val audioHere = state.isLocallyConnected || state.isA2dpConnected
        val airpodsNearby = state.battery.isNotEmpty()
        val (statusText, statusDot) = when {
            audioHere -> "Connected — audio routed here" to Color(0xFF34C759)
            airpodsNearby -> "AirPods on another device or in the case" to Color(0xFFFF9500)
            else -> "AirPods not nearby" to Color(0xFF8E8E93)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(8.dp)
                    .background(statusDot, RoundedCornerShape(50))
            )
            Text(
                statusText,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = SfPro,
                    color = if (dark) Color.White.copy(0.7f) else Color.Black.copy(0.7f)
                )
            )
        }

        // ── Battery ──────────────────────────────────────────────────────────
        BatteryView(
            batteryList = state.battery,
            budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
            caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
        )

        // ── Listening Mode ────────────────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            Spacer(Modifier.height(8.dp))
            if (state.aacpAvailable) {
                NoiseControlSettings(
                    showOffListeningMode = true,
                    noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                    onNoiseControlModeChanged = { viewModel.setControlCommandInt(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it) }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f).alpha(DisabledAlpha.toFloat())) {
                        NoiseControlSettings(
                            showOffListeningMode = true,
                            noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                            onNoiseControlModeChanged = { /* no-op: AACP not available */ }
                        )
                    }
                    io.nikos.propods.presentation.components.RequiresAacpIcon()
                }
            }
        }

        // ── Transparency (Xposed-only) ────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE) && state.vendorIdHook) {
            Spacer(Modifier.height(8.dp))
            NavigationButton(to = "transparency_customization", name = stringResource(R.string.customize_transparency_mode), navController = navController)
        }

        // ── Upgrade banner ────────────────────────────────────────────────
        if (!state.isPremium) {
            Spacer(Modifier.height(8.dp))
            StyledButton(onClick = { navController.navigate("purchase_screen") }, backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(), maxScale = 0.05f,
                surfaceColor = if (dark) Color(0xFF916100) else Color(0xFFE59900)) {
                Text(stringResource(R.string.unlock_advanced_features), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Tile grid fills all remaining vertical space ──────────────────
        CategoryTileGrid(
            modifier = Modifier.weight(1f),
            navController = navController,
            dark = dark
        )
    }
}

@Composable
private fun CategoryTileGrid(
    modifier: Modifier = Modifier,
    navController: NavController,
    dark: Boolean,
) {
    val tileColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black

    // Scale tile typography + iconography based on screen width.
    // Reference: phone is ~360–420 dp wide; tablet is ≥ 600 dp; large tablet ≥ 840 dp.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    // In landscape the three tile rows share a short height, so a width-derived
    // emoji (44sp) overflows and clips. The per-tile emoji size is additionally
    // capped to the actual tile height via BoxWithConstraints below; this is just
    // the upper bound.
    val isLandscape = screenWidthDp > screenHeightDp
    val emojiFontSize = when {
        screenWidthDp >= 840 -> 44.sp
        screenWidthDp >= 600 -> 36.sp
        else                 -> 26.sp
    }
    val emojiSlotWidth = when {
        screenWidthDp >= 840 -> 60.dp
        screenWidthDp >= 600 -> 48.dp
        else                 -> 34.dp
    }
    val labelFontSize = when {
        screenWidthDp >= 840 -> 20.sp
        screenWidthDp >= 600 -> 17.sp
        else                 -> 13.sp
    }
    val tileCorner = if (screenWidthDp >= 600) 24.dp else 18.dp
    val tilePadH   = if (screenWidthDp >= 600) 20.dp else 12.dp
    // Landscape tiles are vertically compressed — trim vertical padding so the
    // icon + label have room and the emoji doesn't get clipped.
    val tilePadV   = when {
        isLandscape          -> 6.dp
        screenWidthDp >= 600 -> 16.dp
        else                 -> 10.dp
    }
    val rowSpacing = if (screenWidthDp >= 600) 12.dp else 8.dp

    val tiles = listOf(
        Triple("controls",    "🎧", "AirPods Controls"),
        Triple("settings",    "⚙️", "AirPods Settings"),
        Triple("smart",       "✨", "Smart Features"),
        Triple("appsettings", "📱", "App Settings"),
        Triple("audio",       "🔊", "Audio & Conn."),
        Triple("help",        "❓", "Help & Support"),
    )
    // Each row takes an equal share of the available height via weight(1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                row.forEach { (key, emoji, label) ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(tileColor, RoundedCornerShape(tileCorner))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { navController.navigate("category/$key") }
                            .padding(horizontal = tilePadH, vertical = tilePadV),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            // Cap the emoji to the available tile height. An emoji
                            // glyph's line box is ~1.3× its font size, so to fit it
                            // vertically we keep fontSize ≤ height / 1.3 (≈ ×0.76).
                            // Use the width breakpoint value as the upper bound.
                            val heightCapSp = maxHeight.value * 0.72f
                            val effectiveEmojiSp = minOf(emojiFontSize.value, heightCapSp).sp
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    emoji,
                                    modifier = Modifier.width(emojiSlotWidth),
                                    style = TextStyle(fontSize = effectiveEmojiSp, fontFamily = SfPro)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    style = TextStyle(fontSize = labelFontSize, fontWeight = FontWeight.Medium,
                                    fontFamily = SfPro, color = textColor))
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
//  DISCONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun DisconnectedScreen(
    state: io.nikos.propods.presentation.viewmodel.AirPodsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    onOpenContact: () -> Unit
) {
    val appState  by appSettingsViewModel.uiState.collectAsState()
    val context   = LocalContext.current
    val backdrop  = rememberLayerBackdrop()
    val cardBg    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black
    val tapCount  = remember { mutableIntStateOf(0) }
    val lastTap   = remember { mutableLongStateOf(0L) }

    LazyColumn(
        Modifier.fillMaxSize()
            .drawBackdrop(rememberLayerBackdrop(), exportedBackdrop = backdrop, shape = { RoundedCornerShape(0.dp) }, highlight = { Highlight.Ambient.copy(alpha = 0f) }, effects = {})
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - lastTap.longValue > 400) tapCount.intValue = 0
                    tapCount.intValue++; lastTap.longValue = now
                    if (tapCount.intValue >= 5) { tapCount.intValue = 0; viewModel.activateDemoMode() }
                })
            },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding + 16.dp)) }

        item(key = "status") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // On devices where the AACP socket never connects (e.g. non-rooted
                // Xiaomi) the AirPods can still be connected over standard Bluetooth
                // A2DP — handover works. Show that accurately instead of "not connected".
                val titleRes = if (state.isA2dpConnected) R.string.connected_via_bluetooth else R.string.airpods_not_connected
                val descRes = if (state.isA2dpConnected) R.string.connected_via_bluetooth_description else R.string.airpods_not_connected_description
                Text(stringResource(titleRes), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = textColor, fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(descRes), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Light, color = textColor.copy(0.7f), fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        // Battery display — shown on AACP-less devices when A2DP is connected.
        // Data arrives via BLE (no AACP needed) so it's already in state.battery.
        if (state.isA2dpConnected && state.battery.isNotEmpty()) {
            item(key = "battery_a2dp") {
                BatteryView(
                    batteryList = state.battery,
                    budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
                    caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
                )
            }
        }

        if (state.connectionSuccessful) {
            item(key = "connection_settings") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection Settings", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Configure Bluetooth and cross-device settings.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(onClick = { navController.navigate("connection_settings") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Settings", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        } else {
            // Cross-device settings — surfaced here on limited-mode devices because
            // the CategoryScreen / AppSettings copies are unreachable (no category
            // grid). The full ConnectionSettings component gives the user the master
            // toggle, peer selector, and reconnect button.
            item(key = "connection_settings") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection Settings", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Configure Bluetooth and cross-device settings.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    ConnectionSettings(
                        crossDeviceEnabled = state.crossDeviceEnabled,
                        onCrossDeviceChanged = { viewModel.setCrossDeviceEnabled(it) },
                        crossDevicePeers = state.crossDevicePeers,
                        navController = navController,
                        automaticEarDetectionEnabled = state.automaticEarDetectionEnabled,
                        onAutomaticEarDetectionChanged = { viewModel.setAutomaticEarDetectionEnabled(it) },
                        automaticConnectionEnabled = state.automaticConnectionEnabled,
                        onAutomaticConnectionChanged = { viewModel.setAutomaticConnectionEnabled(it) },
                        earDetectionAvailable = state.aacpAvailable
                    )
                }
            }
            // Handover toggles — on a limited-mode device the CategoryScreen /
            // AppSettings copies are unreachable (no category grid), so surface the
            // two takeover toggles that gate takeOver("music") / takeOver("call").
            item(key = "handover_settings") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Handover", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    StyledToggle(
                        label = stringResource(R.string.takeover_media_start),
                        description = stringResource(R.string.takeover_media_start_desc),
                        checked = appState.takeoverWhenMediaStart,
                        onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart,
                        independent = true
                    )
                    StyledToggle(
                        label = stringResource(R.string.takeover_ringing_call),
                        description = stringResource(R.string.takeover_ringing_call_desc),
                        checked = appState.takeoverWhenRingingCall,
                        onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall,
                        independent = true
                    )
                }
            }
        }

        if (state.hasSavedDevice || state.connectionSuccessful) {
            item(key = "reconnect") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Reconnect to Previous Device", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Your AirPods were previously connected to this device.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(onClick = { viewModel.reconnectFromSavedMac() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.reconnect_to_last_device), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "find_nearby") {
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find My AirPods", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                Text("Locate your AirPods using Bluetooth signal proximity.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                StyledButton(onClick = { navController.navigate("proximity_finder") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    Text("Find Nearby", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                }
            }
        }

        if (!BuildConfig.PLAY_BUILD) {
            item(key = "troubleshooting") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Troubleshooting", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Can't reconnect? Get help with common connection issues.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(onClick = { navController.navigate("troubleshooting") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.troubleshooting), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "help") {
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Get Help", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                Text("Contact support or join the community.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyledButton(onClick = onOpenContact, backdrop = backdrop, modifier = Modifier.weight(1f)) { Text("Email", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor)) }
                    StyledButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())) }, backdrop = backdrop, modifier = Modifier.weight(1f)) { Text("Discord", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor)) }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
    }
}
