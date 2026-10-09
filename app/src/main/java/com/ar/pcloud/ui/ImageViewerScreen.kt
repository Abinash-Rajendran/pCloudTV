package com.ar.pcloud.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.ui.theme.PCloudColors

/**
 * Full-screen photo viewer.
 *
 * Each photo is fetched at full resolution through `getfilelink` (the grid already
 * showed the `getthumbslinks` thumbnail). Left/right move through the folder's
 * images; the neighbouring links are resolved ahead of time so moving along a
 * folder does not stall on an API round trip per photo.
 */
@Composable
fun ImageViewerScreen(
    items: List<ItemMetadata>,
    initialIndex: Int,
    onResolveUrl: (ItemMetadata, (String?) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    if (items.isEmpty()) {
        onBack()
        return
    }

    var index by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex))
    }
    // fileid -> resolved full-size URL, so going back to a photo is instant.
    val resolved = remember { mutableStateOf(mapOf<Long, String>()) }
    var failed by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val current = items[index]
    val currentUrl = current.fileid?.let { resolved.value[it] }

    BackHandler { onBack() }

    fun resolve(target: ItemMetadata) {
        val fileId = target.fileid ?: return
        if (resolved.value.containsKey(fileId)) return
        onResolveUrl(target) { url ->
            if (url != null) {
                resolved.value = resolved.value + (fileId to url)
            } else if (target.fileid == current.fileid) {
                failed = true
            }
        }
    }

    // Current photo first, then the immediate neighbours.
    LaunchedEffect(index) {
        failed = false
        resolve(current)
        items.getOrNull(index + 1)?.let(::resolve)
        items.getOrNull(index - 1)?.let(::resolve)
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaPrevious -> {
                        if (index > 0) index -= 1
                        true
                    }

                    Key.DirectionRight, Key.MediaNext -> {
                        if (index < items.lastIndex) index += 1
                        true
                    }

                    else -> false
                }
            }
    ) {
        Crossfade(targetState = currentUrl, label = "photo") { url ->
            when {
                url != null -> AsyncImage(
                    model = url,
                    contentDescription = current.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                failed -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Could not load this photo",
                        color = PCloudColors.Error,
                        fontSize = 17.sp
                    )
                }

                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { Spinner() }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 48.dp, vertical = 20.dp)
                .align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.name,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val details = listOfNotNull(
                    "${index + 1} of ${items.size}",
                    if (current.width != null && current.height != null) {
                        "${current.width}x${current.height}"
                    } else {
                        null
                    },
                    formatFileSize(current.size).takeIf { it.isNotEmpty() }
                ).joinToString("  •  ")
                Text(text = details, color = PCloudColors.TextSecondary, fontSize = 13.sp)
            }

            Text(
                text = "◀ ▶ browse   Back to exit",
                color = PCloudColors.TextTertiary,
                fontSize = 13.sp
            )
        }
    }
}
