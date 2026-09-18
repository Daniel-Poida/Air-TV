package io.github.jqssun.airplay.ui

import android.app.Activity
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onPip: () -> Unit = {},
) {
    val pin by viewModel.pinCode.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val videoActive by viewModel.videoPlaybackActive.collectAsState()
    val pending by viewModel.videoSessionPending.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val aspect by viewModel.videoAspect.collectAsState()
    var mirrorFullscreen by remember { mutableStateOf(true) }
    LaunchedEffect(mirroring) { if (mirroring) mirrorFullscreen = true }
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    when {
        videoActive || pending -> MinimalVideoPlayer(viewModel, isInPip)
        (mirroring && mirrorFullscreen && pin == null) || isInPip -> {
            BackHandler(enabled = !isInPip) { mirrorFullscreen = false }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                VideoSurfaceView(onSurfaceAvailable, onSurfaceDestroyed, aspectRatio = aspect)
            }
        }
        else -> MinimalHome(
            viewModel = viewModel,
            video = { VideoSurfaceView(onSurfaceAvailable, onSurfaceDestroyed, aspectRatio = aspect) },
            onFullscreen = { mirrorFullscreen = true },
            showAudioMode = audioOnly,
            audio = {
                val track by viewModel.trackInfo.collectAsState()
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track.title.ifBlank { "Звук с Mac" }, fontSize = 28.sp, textAlign = TextAlign.Center)
                    if (track.artist.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )
    }
    if (pin != null && !isInPip) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPin,
            title = { Text("Введите этот PIN на Mac", textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()) },
            text = { Text(pin!!, fontSize = 52.sp, letterSpacing = 12.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = viewModel::dismissPin, modifier = Modifier.dpadFocus()) { Text("Готово") } }
        )
    }
}

@Composable
private fun MinimalVideoPlayer(viewModel: MainViewModel, isInPip: Boolean) {
    val active by viewModel.videoPlaybackActive.collectAsState()
    val aspect by viewModel.videoPlaybackAspect.collectAsState()
    val playing by viewModel.videoPlaying.collectAsState()
    val buffering by viewModel.videoBuffering.collectAsState()
    val tick by viewModel.videoOverlayTick.collectAsState()
    val position by viewModel.videoPositionMs.collectAsState()
    val duration by viewModel.videoDurationMs.collectAsState()
    val scrub by viewModel.videoScrubPositionMs.collectAsState()
    val title by viewModel.videoTitle.collectAsState()
    var controls by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(tick, playing, active, scrub) {
        controls = !active || !playing || tick > 0L
        if (controls && active && playing && scrub == null) {
            delay(4000)
            controls = false
        }
    }
    LaunchedEffect(controls, isInPip, active, buffering) {
        if (!isInPip) {
            if (controls && active && !buffering) playFocus.requestFocusUntilLanded()
            else rootFocus.requestFocusUntilLanded()
        }
    }
    BackHandler(enabled = !isInPip) { viewModel.stopVideoPlayback() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || isInPip) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { viewModel.seekVideoBy(-10_000); viewModel.showVideoOverlay(); true }
                    Key.DirectionRight, Key.MediaFastForward -> { viewModel.seekVideoBy(10_000); viewModel.showVideoOverlay(); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (controls) viewModel.toggleVideoPlayPause() else viewModel.showVideoOverlay()
                        true
                    }
                    Key.MediaPlayPause -> { viewModel.toggleVideoPlayPause(); true }
                    Key.MediaPlay -> { viewModel.setVideoPlaying(true); true }
                    Key.MediaPause -> { viewModel.setVideoPlaying(false); true }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (!controls) { viewModel.showVideoOverlay(); true } else false
                    }
                    else -> false
                }
            }, contentAlignment = Alignment.Center
    ) {
        VideoSurfaceView(viewModel::onVideoPlaybackSurfaceAvailable,
            viewModel::onVideoPlaybackSurfaceDestroyed, aspectRatio = aspect)
        if (!isInPip && controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = .4f), Color.Transparent, Color.Black.copy(alpha = .8f)))))
            Column(Modifier.align(Alignment.TopStart).padding(40.dp)) {
                Text(title.ifBlank { "Видео с Mac" }, fontSize = 20.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("AirPlay", fontSize = 14.sp, color = Color.White.copy(alpha = .7f))
            }
            if (active && !buffering) {
                FilledIconButton(onClick = { viewModel.toggleVideoPlayPause() },
                    modifier = Modifier.size(72.dp).focusRequester(playFocus).dpadFocus(CircleShape),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .45f), contentColor = Color.White)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Пауза" else "Продолжить", Modifier.size(32.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 28.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMinimalTime(scrub ?: position), fontSize = 14.sp, color = Color.White)
                    Text(if (duration > 0) formatMinimalTime(duration) else "В прямом эфире",
                        fontSize = 14.sp, color = Color.White)
                }
                if (duration > 0) {
                    Slider(
                        value = ((scrub ?: position).toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { viewModel.startVideoScrub(); viewModel.scrubVideoTo((it * duration).toLong()) },
                        onValueChangeFinished = viewModel::endVideoScrub,
                        modifier = Modifier.fillMaxWidth().dpadFocus(),
                    )
                }
            }
        }
        if (!isInPip && (buffering || !active)) {
            CircularProgressIndicator(Modifier.size(48.dp))
        }
    }
}

private fun formatMinimalTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
