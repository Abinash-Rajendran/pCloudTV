package com.ar.pcloud.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.ui.theme.PCloudColors
import kotlinx.coroutines.delay

/**
 * Video playback.
 *
 * Streams the original file from `getfilelink` — full quality, no transcode, no
 * resolution cap. If ExoPlayer cannot decode it (a codec this TV lacks, or a
 * container it won't extract) we ask pCloud to transcode the same file to HLS via
 * `gethlslink` and start over. That second path is capped at 1280x960 by the API,
 * which is why it is a fallback and not the default.
 *
 * Each MediaLink also carries alternate storage hosts; a host-level failure retries
 * those before giving up on the original.
 *
 * Subtitle files found next to the video in the same pCloud folder arrive as
 * [subtitles] and are side-loaded onto the MediaItem; the first is flagged default
 * so it shows without the viewer doing anything.
 *
 * ## Why the controls are hand-built
 *
 * `PlayerView`'s own controller is a View hierarchy inside an `AndroidView`, and
 * Compose does not hand D-pad focus across that boundary: pressing DOWN did
 * nothing, so the subtitle and audio pickers were simply unreachable with a remote.
 * The transport bar below is therefore plain Compose, with `useController = false`
 * leaving PlayerView to do only what it is good at — rendering the video surface and
 * the caption layer.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    item: ItemMetadata,
    link: MediaLink,
    subtitles: List<ResolvedSubtitle>,
    onRequestHlsFallback: ((String) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Index into [primary url, ...fallback hosts].
    var hostIndex by remember(link) { mutableStateOf(0) }
    var hlsRequested by remember(item.fileid) { mutableStateOf(false) }
    var fatalError by remember(link) { mutableStateOf<String?>(null) }
    var isBuffering by remember(link) { mutableStateOf(true) }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember(link) { mutableLongStateOf(0L) }
    var durationMs by remember(link) { mutableLongStateOf(C.TIME_UNSET) }
    var tracks by remember(link) { mutableStateOf(Tracks.EMPTY) }

    // Where a held left/right is scrubbing to, before it is committed.
    var pendingSeekMs by remember(link) { mutableStateOf<Long?>(null) }
    var areControlsVisible by remember { mutableStateOf(false) }

    // The open picker, and — kept after it closes — the button it came from, so
    // focus can be handed straight back there instead of being dropped.
    var openMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    var lastMenu by remember { mutableStateOf<PlayerMenu?>(null) }

    // Deliberately not keyed on `link`: a viewer who sped a film up does not expect
    // it to drop back to normal when the HLS fallback swaps the source underneath.
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    val candidates = remember(link) { listOf(link.url) + link.fallbackUrls }
    val currentUrl = candidates.getOrNull(hostIndex)

    val surfaceFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }

    // One per settings button, so closing a picker can put the highlight back on
    // the button it was opened from.
    val menuFocus = remember {
        PlayerMenu.entries.associateWith { FocusRequester() }
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    // Back unwinds one layer at a time, so a stray press never dumps the viewer out
    // of the film they are watching.
    BackHandler {
        when {
            openMenu != null -> openMenu = null
            areControlsVisible -> areControlsVisible = false
            else -> onBack()
        }
    }

    // Swap the source when we move to another host, keeping the same player.
    LaunchedEffect(currentUrl, subtitles) {
        val url = currentUrl ?: return@LaunchedEffect
        exoPlayer.setMediaItem(buildMediaItem(url, subtitles))
        exoPlayer.prepare()
    }

    // The player is reused across the original -> HLS swap, so the listener outlives
    // any single `link`. Reading these through rememberUpdatedState keeps it from
    // acting on the superseded link's host list after a fallback.
    val lastHostIndex by rememberUpdatedState(candidates.lastIndex)
    val isHlsStream by rememberUpdatedState(link.isHls)
    val requestHls by rememberUpdatedState(onRequestHlsFallback)

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) durationMs = exoPlayer.duration
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlayerError(error: PlaybackException) {
                // A source/network error may just be a bad storage host: try the next.
                if (error.errorCode in SOURCE_ERROR_CODES && hostIndex < lastHostIndex) {
                    hostIndex += 1
                    return
                }

                // Out of hosts, or the device genuinely cannot decode this file.
                // Either way, HLS transcoding is the remaining option.
                if (!isHlsStream && !hlsRequested) {
                    hlsRequested = true
                    requestHls { message -> fatalError = message }
                    return
                }

                fatalError = error.localizedMessage
                    ?: "This video could not be played (${error.errorCodeName})."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            if (durationMs <= 0) durationMs = exoPlayer.duration
            delay(500)
        }
    }

    // Holding left/right fires a burst of key repeats; seeking on each one would
    // restart the stream dozens of times. Instead the target accumulates on screen
    // and only the final position is actually sought, once the viewer lets go.
    LaunchedEffect(pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        delay(SEEK_COMMIT_DELAY_MS)
        exoPlayer.seekTo(target)
        positionMs = target
        pendingSeekMs = null
    }

    // Speed survives the source swap, so it is re-applied rather than set once.
    LaunchedEffect(playbackSpeed, currentUrl) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    // Controls fall away on their own so they are not left sitting over the film.
    LaunchedEffect(areControlsVisible, openMenu, isPlaying) {
        if (!areControlsVisible || openMenu != null) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        areControlsVisible = false
    }

    // Focus follows the mode: the invisible full-screen surface owns the D-pad while
    // the controls are hidden, and hands it to the button row when they come up.
    //
    // Closing a picker is the case worth spelling out. The transport bar is
    // recomposed from nothing when the picker goes away, so whatever had the
    // highlight before is gone and Compose leaves focus nowhere — the viewer's next
    // D-pad press did nothing and the bar looked dead. Focus therefore goes back to
    // the button the picker was opened from, which is also where the viewer's eye
    // already is: pick a subtitle track, land on Subtitles, one press right to Audio
    // or Speed.
    LaunchedEffect(areControlsVisible, openMenu) {
        runCatching {
            when {
                !areControlsVisible -> {
                    // A bar raised later is a fresh visit; it should start on Play
                    // rather than wherever the last one happened to end.
                    lastMenu = null
                    surfaceFocus.requestFocus()
                }

                openMenu != null -> Unit // the picker focuses its own rows

                else -> {
                    // Play is the fallback for the first raise, and for the case
                    // where the button we came from is no longer on the bar (an HLS
                    // swap can drop the audio or subtitle tracks underneath it).
                    val target = lastMenu?.let { menuFocus[it] } ?: controlsFocus
                    if (runCatching { target.requestFocus() }.isFailure) {
                        controlsFocus.requestFocus()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    // See the KDoc: the built-in controller cannot be reached by a
                    // remote through Compose, so only the video and caption layers
                    // of PlayerView are used.
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // The D-pad target while the controls are hidden. Transparent and
        // full-screen: there is nothing to look at, it just has to hold focus.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(surfaceFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (areControlsVisible) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.MediaRewind -> {
                            pendingSeekMs = seekTarget(
                                from = pendingSeekMs ?: exoPlayer.currentPosition,
                                byMs = -seekStepFor(event.nativeKeyEvent.repeatCount),
                                durationMs = exoPlayer.duration
                            )
                            true
                        }

                        Key.DirectionRight, Key.MediaFastForward -> {
                            pendingSeekMs = seekTarget(
                                from = pendingSeekMs ?: exoPlayer.currentPosition,
                                byMs = seekStepFor(event.nativeKeyEvent.repeatCount),
                                durationMs = exoPlayer.duration
                            )
                            true
                        }

                        Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info -> {
                            areControlsVisible = true
                            true
                        }

                        Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            areControlsVisible = true
                            true
                        }

                        Key.MediaPlay -> { exoPlayer.play(); true }
                        Key.MediaPause -> { exoPlayer.pause(); true }
                        else -> false
                    }
                }
        )

        if (fatalError == null && isBuffering && pendingSeekMs == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spinner()
                if (hlsRequested) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Converting for this TV…",
                        color = PCloudColors.TextSecondary,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // Title bar, hidden once playback is underway so it does not sit over the film.
        if (isBuffering || fatalError != null || areControlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 48.dp, vertical = 20.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                item.videoDetailLine()?.let { details ->
                    Text(
                        text = if (link.isHls) "$details • adaptive stream" else details,
                        color = PCloudColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Scrub preview while a held left/right is still accumulating.
        pendingSeekMs?.let { target ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 48.dp, vertical = 64.dp)
                    .fillMaxWidth()
            ) {
                SeekBar(positionMs = target, durationMs = exoPlayer.duration)
            }
        }

        if (areControlsVisible && fatalError == null) {
            TransportBar(
                positionMs = pendingSeekMs ?: positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                hasTextTracks = tracks.countTracksOfType(C.TRACK_TYPE_TEXT) > 0,
                hasMultipleAudioTracks = tracks.countTracksOfType(C.TRACK_TYPE_AUDIO) > 1,
                firstFocus = controlsFocus,
                menuFocus = menuFocus,
                onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onOpenMenu = { menu ->
                    lastMenu = menu
                    openMenu = menu
                },
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }

        when (openMenu) {
            PlayerMenu.SUBTITLES, PlayerMenu.AUDIO -> {
                val kind = requireNotNull(openMenu)
                TrackPicker(
                    menu = kind,
                    tracks = tracks,
                    onSelect = { group, trackIndex ->
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                            )
                            .setTrackTypeDisabled(kind.trackType, false)
                            .build()
                        openMenu = null
                    },
                    onTurnOff = {
                        // Disabling the whole track type is what actually turns
                        // captions off. Clearing the override alone is not enough: a
                        // side-loaded sidecar carries SELECTION_FLAG_DEFAULT, so the
                        // selector picks it again and "Off" appears to do nothing.
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(kind.trackType)
                            .setTrackTypeDisabled(kind.trackType, true)
                            .build()
                        openMenu = null
                    },
                    onDismiss = { openMenu = null }
                )
            }

            PlayerMenu.SPEED -> SpeedPicker(
                current = playbackSpeed,
                onSelect = { speed ->
                    playbackSpeed = speed
                    openMenu = null
                },
                onDismiss = { openMenu = null }
            )

            null -> Unit
        }

        fatalError?.let { message ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PCloudColors.Scrim)
                    .padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Cannot play this video",
                    color = PCloudColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(text = message, color = PCloudColors.TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(24.dp))
                TvButton(onClick = onBack) {
                    Text("Back to files", fontSize = 15.sp, color = PCloudColors.TextPrimary)
                }
            }
        }
    }
}

/**
 * A settings button on the transport bar and the picker behind it.
 *
 * [trackType] is [C.TRACK_TYPE_NONE] for SPEED, which has no tracks of its own.
 */
private enum class PlayerMenu(val trackType: Int, val title: String, val offLabel: String) {
    SUBTITLES(C.TRACK_TYPE_TEXT, "Subtitles", "Off"),
    AUDIO(C.TRACK_TYPE_AUDIO, "Audio track", "Mute track"),
    SPEED(C.TRACK_TYPE_NONE, "Playback speed", "")
}

/**
 * The bottom bar: progress, time, and the buttons the remote can actually reach.
 *
 * Only the buttons that have something behind them are shown — a subtitle button on
 * a file with no text tracks is a dead end the viewer has to discover by pressing it.
 */
@Composable
private fun TransportBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    hasTextTracks: Boolean,
    hasMultipleAudioTracks: Boolean,
    firstFocus: FocusRequester,
    menuFocus: Map<PlayerMenu, FocusRequester>,
    onTogglePlay: () -> Unit,
    onOpenMenu: (PlayerMenu) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .focusGroup()
    ) {
        SeekBar(positionMs = positionMs, durationMs = durationMs)
        Spacer(Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ControlButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (isPlaying) "Pause" else "Play",
                onClick = onTogglePlay,
                modifier = Modifier.focusRequester(firstFocus)
            )
            if (hasTextTracks) {
                MenuButton(
                    menu = PlayerMenu.SUBTITLES,
                    icon = Icons.Default.Subtitles,
                    label = "Subtitles",
                    menuFocus = menuFocus,
                    onOpenMenu = onOpenMenu
                )
            }
            if (hasMultipleAudioTracks) {
                MenuButton(
                    menu = PlayerMenu.AUDIO,
                    icon = Icons.Default.Audiotrack,
                    label = "Audio",
                    menuFocus = menuFocus,
                    onOpenMenu = onOpenMenu
                )
            }
            MenuButton(
                menu = PlayerMenu.SPEED,
                icon = Icons.Default.Speed,
                label = formatSpeed(playbackSpeed),
                menuFocus = menuFocus,
                onOpenMenu = onOpenMenu
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Back to hide  ·  hold ◀ ▶ to scan",
            color = PCloudColors.TextTertiary,
            fontSize = 12.sp
        )
    }
}

/**
 * A transport button that opens a picker, holding the focus requester that picker
 * will hand the highlight back to when it closes.
 */
@Composable
private fun MenuButton(
    menu: PlayerMenu,
    icon: ImageVector,
    label: String,
    menuFocus: Map<PlayerMenu, FocusRequester>,
    onOpenMenu: (PlayerMenu) -> Unit
) {
    ControlButton(
        icon = icon,
        label = label,
        onClick = { onOpenMenu(menu) },
        modifier = menuFocus[menu]?.let { Modifier.focusRequester(it) } ?: Modifier
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PCloudColors.TextPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, fontSize = 14.sp, color = PCloudColors.TextPrimary)
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PCloudColors.Accent)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(positionMs), color = Color.White, fontSize = 14.sp)
            Text(
                text = if (durationMs > 0) formatDuration(durationMs) else "--:--",
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * The list of selectable tracks of one type, plus an off switch.
 *
 * Unsupported tracks are listed but not selectable — a codec this TV lacks should
 * look unavailable rather than vanish, otherwise the viewer is left wondering where
 * the second audio language went.
 */
@Composable
private fun TrackPicker(
    menu: PlayerMenu,
    tracks: Tracks,
    onSelect: (Tracks.Group, Int) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(tracks, menu) { tracks.groups.filter { it.type == menu.trackType } }
    val isAnythingSelected = groups.any { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    }

    // Flattened first so the names can be made unique against each other: rippers
    // routinely stamp one title on every track, and four rows reading the same thing
    // are no use for picking a language.
    val entries = remember(groups) {
        groups.flatMap { group -> (0 until group.length).map { group to it } }
    }
    val names = remember(entries) {
        trackDisplayNames(entries.map { (group, index) -> group.getTrackFormat(index).toNaming() })
    }

    PickerPanel(title = menu.title, onDismiss = onDismiss) { landing ->
        TrackRow(
            label = menu.offLabel,
            isSelected = !isAnythingSelected,
            isEnabled = true,
            onClick = onTurnOff,
            modifier = Modifier.focusRequester(landing)
        )

        entries.forEachIndexed { position, (group, index) ->
            Spacer(Modifier.height(8.dp))
            TrackRow(
                label = names[position],
                isSelected = group.isTrackSelected(index),
                isEnabled = group.isTrackSupported(index),
                onClick = { onSelect(group, index) }
            )
        }
    }
}

/**
 * Playback speed, as a list rather than a ±0.1 stepper.
 *
 * A stepper means a dozen D-pad presses to reach 2x and no way to get back to
 * exactly 1x without counting, which is the wrong trade on a remote.
 */
@Composable
private fun SpeedPicker(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    // Snapped, so the opening focus always lands on exactly one row even if `current`
    // ever drifts off the offered list.
    val focused = remember(current) { nearestOfferedSpeed(current) }

    PickerPanel(title = PlayerMenu.SPEED.title, onDismiss = onDismiss) { landing ->
        PLAYBACK_SPEEDS.forEachIndexed { index, speed ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            TrackRow(
                label = if (speed == 1f) "Normal" else formatSpeed(speed),
                isSelected = speed == current,
                isEnabled = true,
                onClick = { onSelect(speed) },
                modifier = if (speed == focused) Modifier.focusRequester(landing) else Modifier
            )
        }
    }
}

/**
 * The shared dialog chrome: scrim, panel, title, scrolling body.
 *
 * [rows] is handed the FocusRequester for the row that should hold the highlight
 * when the panel opens; exactly one row must claim it, or the D-pad comes up with
 * nothing focused.
 */
@Composable
private fun PickerPanel(
    title: String,
    onDismiss: () -> Unit,
    rows: @Composable ColumnScope.(landing: FocusRequester) -> Unit
) {
    val landing = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { landing.requestFocus() } }

    BackHandler { onDismiss() }

    Box(modifier = Modifier.fillMaxSize().background(PCloudColors.Scrim)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(40.dp)
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PCloudColors.Surface)
                .padding(20.dp)
                .focusGroup()
        ) {
            Text(
                text = title,
                color = PCloudColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(14.dp))

            // A file with eight audio tracks would otherwise run off the screen, and
            // a focusable row scrolls itself into view.
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                rows(landing)
            }

            Spacer(Modifier.height(14.dp))
            Text("Press Back to close", color = PCloudColors.TextTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvButton(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = if (isSelected) PCloudColors.Accent else Color.Transparent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (isEnabled) label else "$label (not supported)",
            fontSize = 14.sp,
            color = if (isEnabled) PCloudColors.TextPrimary else PCloudColors.TextTertiary
        )
    }
}

/** The naming-relevant part of a track's Format, so the rules below can be tested. */
internal data class TrackNaming(
    val label: String?,
    val language: String?,
    val channelCount: Int? = null
)

private fun Format.toNaming(): TrackNaming = TrackNaming(
    label = label,
    language = language,
    channelCount = channelCount.takeIf { it != Format.NO_VALUE && it > 0 }
)

/**
 * What to call each track in the picker, chosen against the others rather than alone.
 *
 * A label is only used when it actually tells one track from the rest. Rippers stamp
 * the same release name on every stream — the Harry Potter rips list four audio
 * tracks all labelled "www.TamilRockers.im" — and four identical rows are worse than
 * no labels at all, because the viewer has to pick one and listen to find out what it
 * was. When the labels collide the language takes over, then the channel layout, and
 * a number is the last resort so two otherwise indistinguishable tracks stay
 * separately selectable.
 */
internal fun trackDisplayNames(tracks: List<TrackNaming>): List<String> {
    val labels = tracks.map { it.label?.trim()?.takeIf(String::isNotEmpty) }
    val languages = tracks.map { it.languageName() }

    val names = tracks.indices.map { i ->
        val distinctLabel = labels[i]?.takeIf { label -> labels.count { it == label } == 1 }
        distinctLabel ?: languages[i] ?: labels[i]
    }

    return tracks.indices.map { i ->
        val name = names[i] ?: return@map "Track ${i + 1}"
        if (names.count { it == name } == 1) return@map name

        val channels = tracks[i].channelCount?.let(::channelLayoutName)
        val channelsAreDistinct = channels != null && tracks.indices.count {
            names[it] == name && tracks[it].channelCount == tracks[i].channelCount
        } == 1
        if (channelsAreDistinct) "$name • $channels" else "$name (${i + 1})"
    }
}

private fun TrackNaming.languageName(): String? = language
    ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    ?.let { code -> java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }

private fun channelLayoutName(channelCount: Int): String = when (channelCount) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channelCount ch"
}

private fun Tracks.countTracksOfType(trackType: Int): Int =
    groups.filter { it.type == trackType }.sumOf { it.length }

/**
 * The video plus any sidecar subtitle files, as one MediaItem.
 *
 * The first subtitle carries SELECTION_FLAG_DEFAULT, which is what makes it appear
 * without the viewer opening the track picker. `label` is what that picker shows.
 */
private fun buildMediaItem(url: String, subtitles: List<ResolvedSubtitle>): MediaItem {
    if (subtitles.isEmpty()) return MediaItem.fromUri(url)

    val configurations = subtitles.mapIndexed { index, subtitle ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
            .setMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label)
            .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    return MediaItem.Builder()
        .setUri(url)
        .setSubtitleConfigurations(configurations)
        .build()
}

/**
 * How far one left/right press jumps, growing while the button is held.
 *
 * Android repeats a held D-pad key roughly every 50ms, so this reaches minute-sized
 * strides inside a second and five-minute strides shortly after — enough to cross a
 * three-hour film without the viewer lifting a finger, while a single tap is still
 * the familiar 10 seconds.
 */
internal fun seekStepFor(repeatCount: Int): Long = when {
    repeatCount == 0 -> 10_000L
    repeatCount < 6 -> 30_000L
    repeatCount < 14 -> 60_000L
    else -> 300_000L
}

internal fun seekTarget(from: Long, byMs: Long, durationMs: Long): Long {
    val target = (from + byMs).coerceAtLeast(0L)
    // duration is TIME_UNSET (a large negative) until the stream is ready.
    return if (durationMs > 0) target.coerceAtMost(durationMs) else target
}

/**
 * Offered playback rates.
 *
 * Capped at 2x: past that ExoPlayer's time-stretcher turns speech into noise, so a
 * faster option would only be a way to make the film unwatchable. 0.75x earns its
 * place on dialogue-heavy or accented material.
 */
internal val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * The offered rate closest to [speed].
 *
 * The current speed always comes from [PLAYBACK_SPEEDS] today, but the settings
 * panel focuses the matching row on open, and an exact float comparison that misses
 * would leave the panel with nothing focused and an unresponsive D-pad. Snapping is
 * the cheap way to make that impossible.
 */
internal fun nearestOfferedSpeed(speed: Float): Float =
    PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1f

/** "1.5x", and "1x" rather than "1.0x". */
internal fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "${text.trimEnd('.')}x"
}

/** How long after the last scrub key the seek is actually issued. */
private const val SEEK_COMMIT_DELAY_MS = 450L

/** How long the transport bar stays up with no input. */
private const val CONTROLS_TIMEOUT_MS = 6_000L

/**
 * Errors that point at the storage host rather than the file itself — worth
 * retrying against another host from the link's `hosts` array. Decoder errors are
 * deliberately absent: those need the HLS transcode, not a different host.
 */
private val SOURCE_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
)
