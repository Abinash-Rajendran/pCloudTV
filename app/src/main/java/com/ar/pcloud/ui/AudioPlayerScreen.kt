package com.ar.pcloud.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.ui.theme.PCloudColors
import kotlinx.coroutines.delay

/**
 * Now-playing screen for music.
 *
 * The stream comes from `getaudiolink`, which transcodes to mp3 — that is how FLAC,
 * ALAC and Opus end up playable on TV boxes with a narrow decoder set. Album art is
 * a `getthumblink` thumbnail of the track itself, available only when the metadata
 * says `thumb == true`. Artist/album/title come from the pCloud audio tag fields.
 *
 * Remote mapping: left/right seek by 10s, up/down change track, centre toggles
 * play/pause, so the common actions need no on-screen focus at all.
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioPlayerScreen(
    queue: List<ItemMetadata>,
    index: Int,
    link: MediaLink,
    isTranscoded: Boolean,
    onSkip: (Int) -> Unit,
    onLoadArt: (ItemMetadata, (String?) -> Unit) -> Unit,
    onRequestTranscode: ((String) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val track = queue.getOrNull(index) ?: return
    val context = LocalContext.current

    // The folder listing already carries the track's length (`duration`, a
    // float-in-a-string), and it is the only length available on the transcoded
    // path: pCloud produces that mp3 on the fly, so it arrives chunked with no
    // Content-Length and no Xing header and ExoPlayer reports TIME_UNSET forever.
    // On the original stream the player's own figure is better, so it wins once it
    // exists.
    val metadataDurationMs = remember(track.fileid) {
        track.durationSeconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
    }

    // None of the playback state is keyed on `link`, and that is load-bearing rather
    // than an oversight. The player outlives any one track — it is created once and
    // re-pointed at each new URL — so the listener and the progress ticker below are
    // created once too, closing over whichever MutableState objects existed at the
    // time. Re-keying these on `link` would hand every later track a *fresh* state
    // object while the listener went on writing to the discarded one: the second
    // track played perfectly but the screen was left holding its initial values, so
    // it read "Buffering…" and "Loading…" over audio that was clearly running.
    // A single state object per screen, reset by hand in [LaunchedEffect] below, is
    // what keeps the two in touch.
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playerDurationMs by remember { mutableLongStateOf(-1L) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var artUrl by remember(track.fileid) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var transcodeRequested by remember { mutableStateOf(isTranscoded) }

    val durationMs = if (playerDurationMs > 0) playerDurationMs else metadataDurationMs ?: -1L

    val focusRequester = remember { FocusRequester() }

    BackHandler { onBack() }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    // A new URL means a new stream: clear everything the old one left behind before
    // the player starts reporting on the new one. Done here rather than through
    // `remember(link)` keys for the reason given above.
    LaunchedEffect(link.url) {
        error = null
        isBuffering = true
        positionMs = 0L
        playerDurationMs = -1L
        pendingSeekMs = null
        exoPlayer.setMediaItem(MediaItem.fromUri(link.url))
        exoPlayer.prepare()
    }

    // The transcode is a per-track last resort, so the flag follows the track and not
    // the link — the fallback itself changes the link, and resetting on that would
    // let a failing track ask for the same transcode over and over.
    LaunchedEffect(track.fileid) {
        transcodeRequested = isTranscoded
    }

    LaunchedEffect(track.fileid) {
        onLoadArt(track) { url -> artUrl = url }
    }

    // The listener outlives any single track (it is bound to the player, which is
    // reused across tracks), so it must not capture index/queue/onSkip directly —
    // those change on every skip and a captured copy would go stale immediately.
    val currentIndex by rememberUpdatedState(index)
    val lastIndex by rememberUpdatedState(queue.lastIndex)
    val skip by rememberUpdatedState(onSkip)
    val requestTranscode by rememberUpdatedState(onRequestTranscode)

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playerDurationMs = exoPlayer.duration
                }
                // Roll on to the next track when this one finishes.
                if (state == Player.STATE_ENDED && currentIndex < lastIndex) {
                    skip(1)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // FLAC, ALAC and Opus defeat the decoder set on plenty of TV boxes.
                // pCloud will transcode the same file to mp3; that is what the
                // audio endpoint is for, so it is the fallback rather than the
                // default. Only ever asked for once per track.
                if (!transcodeRequested) {
                    transcodeRequested = true
                    requestTranscode { message -> error = message }
                    return
                }
                error = e.localizedMessage ?: "Playback failed (${e.errorCodeName})."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Progress ticker. 500ms is smooth enough for a progress bar and cheap.
    // While a scrub is pending the thumb follows `pendingSeekMs` instead, so the bar
    // tracks the button rather than snapping back to the un-sought position.
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (pendingSeekMs == null) positionMs = exoPlayer.currentPosition
            if (playerDurationMs <= 0) playerDurationMs = exoPlayer.duration
            delay(500)
        }
    }

    // Debounced seek: a held button bumps the target many times a second, and acting
    // on each one would restart the transcoded stream dozens of times over.
    LaunchedEffect(pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        delay(SEEK_COMMIT_DELAY_MS)
        exoPlayer.seekTo(target)
        positionMs = target
        pendingSeekMs = null
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(PCloudColors.Background)
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaRewind -> {
                        pendingSeekMs = seekTarget(
                            from = pendingSeekMs ?: exoPlayer.currentPosition,
                            byMs = -seekStepFor(event.nativeKeyEvent.repeatCount),
                            durationMs = durationMs
                        )
                        true
                    }

                    Key.DirectionRight, Key.MediaFastForward -> {
                        pendingSeekMs = seekTarget(
                            from = pendingSeekMs ?: exoPlayer.currentPosition,
                            byMs = seekStepFor(event.nativeKeyEvent.repeatCount),
                            durationMs = durationMs
                        )
                        true
                    }

                    Key.DirectionUp -> {
                        if (index > 0) onSkip(-1)
                        true
                    }

                    Key.DirectionDown -> {
                        if (index < queue.lastIndex) onSkip(1)
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        true
                    }

                    Key.MediaPlay -> { exoPlayer.play(); true }
                    Key.MediaPause -> { exoPlayer.pause(); true }
                    Key.MediaNext -> { if (index < queue.lastIndex) onSkip(1); true }
                    Key.MediaPrevious -> { if (index > 0) onSkip(-1); true }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(url = artUrl)

        Spacer(Modifier.width(48.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                color = PCloudColors.TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            track.artist?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = PCloudColors.Accent, fontSize = 20.sp, maxLines = 1)
            }
            track.album?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    color = PCloudColors.TextSecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(32.dp))

            PlaybackState(isPlaying = isPlaying, isBuffering = isBuffering)

            Spacer(Modifier.height(20.dp))

            ProgressRow(
                positionMs = pendingSeekMs ?: positionMs,
                durationMs = durationMs,
                isSeeking = pendingSeekMs != null,
                isBuffering = isBuffering
            )

            error?.let { message ->
                Spacer(Modifier.height(20.dp))
                Text(text = message, color = PCloudColors.Error, fontSize = 15.sp)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Track ${index + 1} of ${queue.size}",
                color = PCloudColors.TextTertiary,
                fontSize = 14.sp
            )
            queue.getOrNull(index + 1)?.let { next ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Up next  ·  ${next.displayTitle}",
                    color = PCloudColors.TextSecondary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "◀ ▶ seek (hold to go faster)   ▲ ▼ change track   OK play/pause",
                color = PCloudColors.TextTertiary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AlbumArt(url: String?) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(PCloudColors.SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = PCloudColors.Audio,
                modifier = Modifier.size(96.dp)
            )
        }
    }
}

/**
 * Whether the track is playing, as an indicator rather than a button row.
 *
 * There used to be focusable skip/play/skip buttons here. They could not be reached:
 * the screen's own key handler takes up/down for track changes and left/right for
 * seeking, so nothing ever moved focus onto them — and if it had, they would only
 * have duplicated keys the remote already has. One glanceable state icon says the
 * same thing without pretending to be operable.
 */
@Composable
private fun PlaybackState(isPlaying: Boolean, isBuffering: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (isPlaying) "Playing" else "Paused",
            tint = PCloudColors.Accent,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = when {
                isBuffering -> "Buffering…"
                isPlaying -> "Playing"
                else -> "Paused"
            },
            color = PCloudColors.TextSecondary,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun ProgressRow(
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    isBuffering: Boolean
) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth(0.85f)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(PCloudColors.SurfaceVariant)
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
            Text(
                text = formatDuration(positionMs),
                color = if (isSeeking) PCloudColors.Accent else PCloudColors.TextSecondary,
                fontSize = 14.sp
            )
            Text(
                // "Loading…" only while nothing has played yet. Once the track is
                // running, a length that is still unknown is not going to arrive,
                // and leaving "Loading…" up forever reads as a stuck player.
                text = when {
                    durationMs > 0 -> formatDuration(durationMs)
                    isBuffering && positionMs == 0L -> "Loading…"
                    else -> "--:--"
                },
                color = PCloudColors.TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * How long after the last scrub key the seek is actually issued.
 *
 * Matches the video player, and matters more here: every seek on a transcoded
 * stream costs a fresh HTTP range request to pCloud.
 */
private const val SEEK_COMMIT_DELAY_MS = 450L
