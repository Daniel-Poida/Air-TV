package io.github.jqssun.airplay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** TV-first idle screen. Transport, pairing and rendering stay in the receiver engine. */
@Composable
fun MinimalHome(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    showAudioMode: Boolean,
    audio: @Composable () -> Unit,
) {
    val state by viewModel.serverState.collectAsState()
    val name by viewModel.serverName.collectAsState()
    val notice by viewModel.connectionNotice.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    var settings by remember { mutableStateOf(false) }
    val requestedSettings by viewModel.settingsRequest.collectAsState()
    LaunchedEffect(requestedSettings) { if (requestedSettings) { settings = true; viewModel.clearSettingsRequest() } }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val running = state == ServerState.RUNNING
    LaunchedEffect(settings) { if (!settings) focus.requestFocusUntilLanded() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 28.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Cast, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text("air tv", fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(
                    if (running) MaterialTheme.colorScheme.primary else if (state == ServerState.ERROR)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Text(when (state) {
                    ServerState.RUNNING -> if (connections > 0) "Устройство подключено" else "Готов к подключению"
                    ServerState.STOPPED -> "Приём выключен"
                    ServerState.ERROR -> "Ошибка запуска"
                }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(Modifier.align(Alignment.Center).fillMaxWidth(0.85f),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (running && showAudioMode && connections > 0) {
                Box(Modifier.height(220.dp).fillMaxWidth()) { audio() }
            } else if (running && mirroring) {
                Box(Modifier.height(220.dp).aspectRatio(16f / 9f)) { video() }
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onFullscreen, modifier = Modifier.dpadFocus()) { Text("На весь экран") }
            } else {
                Icon(Icons.Default.Cast, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(name, fontSize = 48.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
                    maxLines = 2, lineHeight = 54.sp)
                Spacer(Modifier.height(16.dp))
                Text(when {
                    state == ServerState.ERROR -> "Не удалось запустить AirPlay. Попробуйте включить приём снова."
                    !running -> "Включите приём, чтобы подключить Mac."
                    notice.isNotEmpty() -> notice
                    connections > 0 -> "Ожидаем видео…"
                    else -> "Ваш Mac. Большой экран."
                }, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                if (running && connections == 0) {
                    Spacer(Modifier.height(26.dp))
                    Text("На Mac выберите это имя в меню AirPlay плеера\nили в «Пункт управления → Повтор экрана».",
                        fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AirTvNavigation(section = "AirPlay", running = running,
                onSettings = { settings = true }, onAirPlay = {},
                onAirDrop = { context.startActivity(Intent(context, io.github.jqssun.airplay.files.FilesActivity::class.java)) },
                onToggleReceiver = { if (running) viewModel.stopServer() else viewModel.startServer() }, initialFocus = focus)
            Spacer(Modifier.height(12.dp))
            Text("Mac и телевизор должны быть в одной локальной сети", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
        }
    }
    if (settings) MinimalSettings(viewModel) { settings = false }
}

@Composable
private fun MinimalSettings(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val name by viewModel.serverName.collectAsState()
    val pin by viewModel.requirePin.collectAsState()
    val downloads by viewModel.saveDownloads.collectAsState()
    val storagePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.setSaveDownloads(true) }
    val background by viewModel.runInBackground.collectAsState()
    val boot by viewModel.bootAutoStart.collectAsState()
    val launch by viewModel.launchOnConnect.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var canOpen by remember { mutableStateOf(Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)) }
    var permissionMenuMissing by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOpen = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var draft by remember { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки Air TV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(40) },
                    label = { Text("Имя в AirPlay") }, singleLine = true,
                    isError = draft.trim().isEmpty(), modifier = Modifier.fillMaxWidth())
                SettingToggle("Требовать PIN", "Для AirPlay и передачи файлов через браузер", pin, viewModel::setRequirePin)
                SettingToggle("Сохранять в Downloads", "Новые файлы — в папку загрузок телевизора", downloads) { value ->
                    if (value && Build.VERSION.SDK_INT < 29 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else viewModel.setSaveDownloads(value)
                }
                SettingToggle("Принимать в фоне", "Оставаться доступным после выхода из приложения", background,
                    viewModel::setRunInBackground)
                SettingToggle("Открывать при трансляции", "Показывать PIN и видео поверх текущего приложения", launch,
                    viewModel::setLaunchOnConnect)
                if (launch && !canOpen) {
                    Text("Для автоматического открытия разрешите Air TV показ поверх других приложений.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                        try {
                            context.startActivity(intent)
                        } catch (_: android.content.ActivityNotFoundException) {
                            permissionMenuMissing = true
                        }
                    }, modifier = Modifier.dpadFocus()) { Text("Разрешить автоматическое открытие") }
                }
                SettingToggle("Автозапуск при включении телевизора", "Запускать Air TV после загрузки системы", boot,
                    viewModel::setBootAutoStart)
                Text("Видео и повтор экрана по AirPlay. Защищённое DRM-видео не поддерживается.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.setServerName(draft.trim()); onDismiss() },
                enabled = draft.trim().isNotEmpty(), modifier = Modifier.focusRequester(focus).dpadFocus()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.dpadFocus()) { Text("Закрыть") } }
    )
    if (permissionMenuMissing) {
        AlertDialog(
            onDismissRequest = { permissionMenuMissing = false },
            title = { Text("Разрешение через компьютер") },
            text = { Text("На этом телевизоре нет меню для разрешения «Поверх других приложений». Его можно включить через ADB с подключённого компьютера. Приём в фоне уже работает; без разрешения экран трансляции нужно открывать вручную.") },
            confirmButton = {
                Button(onClick = { permissionMenuMissing = false }, modifier = Modifier.dpadFocus()) { Text("Понятно") }
            }
        )
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    // One focus target per row; DPAD center toggles via Switch's standard semantics.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.dpadFocus())
    }
}
