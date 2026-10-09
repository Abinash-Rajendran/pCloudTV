package com.ar.pcloud.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.ar.pcloud.data.model.Breadcrumb
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.PCloudFileType
import com.ar.pcloud.ui.theme.PCloudColors

@Composable
fun FolderBrowserScreen(
    uiState: UiState,
    onItemClick: (ItemMetadata) -> Unit,
    onBreadcrumbClick: (Breadcrumb) -> Unit,
    onRefreshClick: () -> Unit,
    onToggleListView: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val firstItemFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    // Purely presentational, so it lives here rather than in UiState.
    var isMenuOpen by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().background(PCloudColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // TV overscan: content must stay clear of the bezel on real sets.
                .padding(horizontal = 48.dp, vertical = 27.dp)
                // Deactivating the group takes its children out of the focus graph
                // too, so the D-pad cannot wander into the grid behind the scrim.
                // Applied only while the menu is up: `canFocus = true` on a focus
                // group makes the group node itself focusable, which would let the
                // whole screen swallow focus instead of passing it to a card.
                .then(
                    if (isMenuOpen) {
                        Modifier.focusProperties { canFocus = false }
                    } else {
                        Modifier
                    }
                )
                .focusGroup()
        ) {
            BrowserHeader(onMenuClick = { isMenuOpen = true }, menuFocus = menuFocus)
            Spacer(Modifier.height(20.dp))
            BreadcrumbTrail(
                breadcrumbs = uiState.breadcrumbs,
                onBreadcrumbClick = onBreadcrumbClick
            )
            Spacer(Modifier.height(20.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    uiState.isLoading -> CenteredMessage { Spinner() }

                    // Only covers the grid when there is nothing to show behind it; an
                    // error on a loaded folder appears as the banner further down.
                    uiState.errorMessage != null && uiState.items.isEmpty() -> CenteredMessage {
                        // A failed folder used to leave nothing focusable on screen, so
                        // the remote did nothing at all and Back was the only way out —
                        // for a dropped network, which is the common case, Try again is
                        // exactly what the viewer wants.
                        LaunchedEffect(uiState.errorMessage) {
                            runCatching { retryFocus.requestFocus() }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.errorMessage,
                                color = PCloudColors.Error,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            TvButton(
                                onClick = onRefreshClick,
                                modifier = Modifier.focusRequester(retryFocus)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = PCloudColors.TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Try again",
                                    fontSize = 15.sp,
                                    color = PCloudColors.TextPrimary
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (uiState.breadcrumbs.size > 1) {
                                    "Press Back to go up a folder"
                                } else {
                                    "Press Back to dismiss"
                                },
                                color = PCloudColors.TextTertiary,
                                fontSize = 14.sp
                            )
                        }
                    }

                    uiState.items.isEmpty() -> CenteredMessage {
                        Text(
                            text = "This folder is empty",
                            color = PCloudColors.TextTertiary,
                            fontSize = 18.sp
                        )
                    }

                    else -> {
                        // A new folder must start at the top. The scroll state is
                        // remembered across navigation, so without this, going into a
                        // folder from halfway down a long list opens the new one halfway
                        // down too — and since item 0 is then outside the viewport it is
                        // not composed, so the focus request below finds nothing attached
                        // and the D-pad is left stranded on the breadcrumb.
                        //
                        // Switching layout rebuilds every item and drops focus with them,
                        // so the view mode is a key here as well. The retry covers the
                        // frames before the lazy container has attached its first child.
                        LaunchedEffect(uiState.items, uiState.isListView) {
                            if (uiState.isListView) {
                                listState.scrollToItem(0)
                            } else {
                                gridState.scrollToItem(0)
                            }
                            repeat(FOCUS_ATTEMPTS) {
                                if (runCatching { firstItemFocus.requestFocus() }.isSuccess) {
                                    return@LaunchedEffect
                                }
                                delay(FOCUS_RETRY_MS)
                            }
                        }

                        // LazyVerticalGrid throws IllegalArgumentException("Key ... was
                        // already used") the moment two items share a key — it surfaces on
                        // the first measure past the visible rows, i.e. as soon as the user
                        // scrolls, which reads as "the app crashed on the home screen".
                        // ItemMetadata.stableKey falls back to the numeric ids when the
                        // server omits `id`, and those collapse to "f0"/"d0" when the ids
                        // are absent too, so uniqueness has to be enforced here rather than
                        // assumed from the response.
                        val keyedItems = remember(uiState.items) { uniqueKeys(uiState.items) }

                        if (uiState.isListView) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = keyedItems,
                                    key = { it.key }
                                ) { keyed ->
                                    val item = keyed.item
                                    MediaRow(
                                        item = item,
                                        thumbnailUrl = item.fileid?.let { uiState.thumbnails[it] },
                                        onClick = { onItemClick(item) },
                                        modifier = if (keyed === keyedItems.first()) {
                                            Modifier.focusRequester(firstItemFocus)
                                        } else {
                                            Modifier
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 240.dp),
                                contentPadding = PaddingValues(bottom = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = keyedItems,
                                    key = { it.key }
                                ) { keyed ->
                                    val item = keyed.item
                                    val isFirst = keyed === keyedItems.first()
                                    MediaCard(
                                        item = item,
                                        thumbnailUrl = item.fileid?.let { uiState.thumbnails[it] },
                                        onClick = { onItemClick(item) },
                                        modifier = if (isFirst) {
                                            Modifier.focusRequester(firstItemFocus)
                                        } else {
                                            Modifier
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // A file that would not open, with a usable grid behind it. A banner
                // keeps the folder visible; Back dismisses it.
                if (uiState.errorMessage != null && uiState.items.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PCloudColors.Surface)
                            .border(1.dp, PCloudColors.Error, RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = PCloudColors.Error,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "Back to dismiss",
                            color = PCloudColors.TextTertiary,
                            fontSize = 13.sp
                        )
                    }
                }

                // Resolving a stream link takes a moment; say so rather than appearing stuck.
                if (uiState.isResolvingMedia) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(PCloudColors.Scrim),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spinner()
                            Spacer(Modifier.height(16.dp))
                            Text("Opening…", color = PCloudColors.TextSecondary, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // Outside the padded Column so the scrim covers the whole screen.
        if (isMenuOpen) {
            AccountMenu(
                uiState = uiState,
                onDismiss = {
                    isMenuOpen = false
                    // Send focus back to the hamburger it came from, or the TV is
                    // left with nothing selected and the D-pad does nothing.
                    runCatching { menuFocus.requestFocus() }
                },
                onRefresh = {
                    isMenuOpen = false
                    onRefreshClick()
                },
                onToggleListView = {
                    isMenuOpen = false
                    onToggleListView()
                },
                onLogout = {
                    isMenuOpen = false
                    onLogoutClick()
                }
            )
        }
    }
}

/**
 * The hamburger panel: who is signed in, how full the account is, and the two
 * actions. Sign out lives in here — and behind a confirmation — because on the
 * header it was one stray D-pad press from the grid and got hit by accident.
 */
@Composable
private fun AccountMenu(
    uiState: UiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onToggleListView: () -> Unit,
    onLogout: () -> Unit
) {
    var isConfirmingLogout by remember { mutableStateOf(false) }
    val firstAction = remember { FocusRequester() }
    val cancelButton = remember { FocusRequester() }

    // Back steps out of the confirmation first, then closes the menu. Composed
    // below the app-level handler, so it wins and Back never leaves the folder
    // while the menu is up.
    BackHandler {
        if (isConfirmingLogout) isConfirmingLogout = false else onDismiss()
    }

    LaunchedEffect(isConfirmingLogout) {
        runCatching {
            if (isConfirmingLogout) cancelButton.requestFocus() else firstAction.requestFocus()
        }
    }

    // The scrim is deliberately not clickable: a TV has no pointer, so "tap outside
    // to dismiss" buys nothing, and a clickable Modifier is focusable — the D-pad
    // landed on the scrim instead of Sign out and the next OK press closed the menu.
    Box(modifier = Modifier.fillMaxSize().background(PCloudColors.Scrim)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 48.dp, vertical = 27.dp)
                .width(380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PCloudColors.Surface)
                .padding(24.dp)
                .focusGroup()
        ) {
            Text(
                text = uiState.userEmail ?: "Signed in",
                color = PCloudColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = uiState.selectedRegion.displayName,
                color = PCloudColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Only shown once userinfo has told us the account's size.
            if (uiState.quotaBytes > 0) {
                Spacer(Modifier.height(18.dp))
                StorageMeter(
                    usedBytes = uiState.usedQuotaBytes,
                    totalBytes = uiState.quotaBytes
                )
            }

            Spacer(Modifier.height(22.dp))

            if (isConfirmingLogout) {
                Text(
                    text = "Sign out of ${uiState.userEmail ?: "this account"}?",
                    color = PCloudColors.TextPrimary,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "You will need your password to sign back in.",
                    color = PCloudColors.TextTertiary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Cancel is focused first, so a stray OK press is harmless.
                    TvButton(
                        onClick = { isConfirmingLogout = false },
                        modifier = Modifier.focusRequester(cancelButton)
                    ) {
                        Text("Cancel", fontSize = 14.sp, color = PCloudColors.TextPrimary)
                    }
                    TvButton(onClick = onLogout) {
                        Text("Sign out", fontSize = 14.sp, color = PCloudColors.Error)
                    }
                }
            } else {
                MenuAction(
                    icon = Icons.Default.Refresh,
                    label = "Refresh this folder",
                    onClick = onRefresh,
                    modifier = Modifier.focusRequester(firstAction)
                )
                Spacer(Modifier.height(10.dp))
                // Labelled with the destination, not the current state, so there is
                // nothing to misread from across the room.
                MenuAction(
                    icon = if (uiState.isListView) {
                        Icons.Default.GridView
                    } else {
                        Icons.AutoMirrored.Filled.ViewList
                    },
                    label = if (uiState.isListView) "Switch to grid view" else "Switch to list view",
                    onClick = onToggleListView
                )
                Spacer(Modifier.height(10.dp))
                MenuAction(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    label = "Sign out",
                    tint = PCloudColors.Error,
                    onClick = { isConfirmingLogout = true }
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Press Back to close",
                color = PCloudColors.TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}

/** Full-width row in the menu panel. */
@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PCloudColors.TextPrimary
) {
    TvButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text = label, fontSize = 15.sp, color = tint)
    }
}

/**
 * "1.2 TB of 2.0 TB used · 800 GB free", with a fill bar. Same construction as the
 * audio player's progress bar (AudioPlayerScreen.ProgressRow).
 */
@Composable
private fun StorageMeter(usedBytes: Long, totalBytes: Long) {
    val fraction = (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    // A clock skew or a quota change could in principle put used above total;
    // clamping keeps "free" from rendering as a negative size.
    val freeBytes = (totalBytes - usedBytes).coerceAtLeast(0L)

    Column(modifier = Modifier.fillMaxWidth()) {
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
                    .background(if (fraction > 0.9f) PCloudColors.Error else PCloudColors.Accent)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${formatFileSize(usedBytes)} of ${formatFileSize(totalBytes)} used",
            color = PCloudColors.TextSecondary,
            fontSize = 13.sp
        )
        Text(
            text = "${formatFileSize(freeBytes)} free",
            color = PCloudColors.TextTertiary,
            fontSize = 13.sp
        )
    }
}

/**
 * Just the wordmark and the hamburger. The account line and Sign out moved into
 * [AccountMenu]: a Sign out button sitting here was adjacent to the grid in the
 * focus order and kept being hit by mistake.
 */
@Composable
private fun BrowserHeader(onMenuClick: () -> Unit, menuFocus: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "pCloud",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = PCloudColors.TextPrimary
        )

        TvButton(onClick = onMenuClick, modifier = Modifier.focusRequester(menuFocus)) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Account and settings",
                tint = PCloudColors.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun BreadcrumbTrail(
    breadcrumbs: List<Breadcrumb>,
    onBreadcrumbClick: (Breadcrumb) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    color = PCloudColors.TextTertiary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            val isCurrent = index == breadcrumbs.lastIndex
            TvButton(
                onClick = { onBreadcrumbClick(crumb) },
                // The current folder is a label, not a destination.
                enabled = !isCurrent
            ) {
                Text(
                    text = crumb.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) PCloudColors.Accent else PCloudColors.TextPrimary
                )
            }
        }
    }
}

/**
 * One grid tile. Shows the pCloud thumbnail when the file has one (`thumb == true`
 * in the metadata), otherwise a category icon.
 *
 * Focus is drawn with a scale-up plus an accent border: on a TV the user is metres
 * away with no pointer, so the focused tile has to be obvious at a glance.
 */
@Composable
private fun MediaCard(
    item: ItemMetadata,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.06f else 1f, label = "cardScale")

    val (icon, tint) = iconFor(item.fileType)

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) PCloudColors.SurfaceFocused else PCloudColors.SurfaceVariant)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) PCloudColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(PCloudColors.Surface),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(48.dp)
                )
            }

            // Play affordance on anything streamable.
            if (item.fileType.isPlayableMedia) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black.copy(alpha = if (thumbnailUrl != null) 0.55f else 0f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailUrl != null) {
                        Icon(
                            imageVector = if (item.fileType == PCloudFileType.AUDIO) {
                                Icons.Default.MusicNote
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            item.formattedDuration()?.let { duration ->
                Text(
                    text = duration,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = item.displayTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = PCloudColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.detailLine(),
                fontSize = 12.sp,
                color = PCloudColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One list row — the same item as [MediaCard], laid out for scanning names rather
 * than for looking at pictures.
 *
 * Long filenames are what the list is for (a release name is unreadable truncated
 * into a 240.dp tile), so the title gets two lines here and the thumbnail shrinks
 * to a small leading tile. Focus is a border and a tint rather than a scale-up:
 * growing a full-width row pushes its neighbours around.
 */
@Composable
private fun MediaRow(
    item: ItemMetadata,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val (icon, tint) = iconFor(item.fileType)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) PCloudColors.SurfaceFocused else PCloudColors.SurfaceVariant)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) PCloudColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(PCloudColors.Surface),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = PCloudColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.detailLine(),
                fontSize = 12.sp,
                color = PCloudColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // In the grid this sits over the thumbnail; the row has no space for that,
        // so the duration goes on the trailing edge where it lines up down the list.
        item.formattedDuration()?.let { duration ->
            Spacer(Modifier.width(16.dp))
            Text(
                text = duration,
                fontSize = 13.sp,
                color = PCloudColors.TextSecondary
            )
        }

        if (item.fileType == PCloudFileType.FOLDER) {
            Spacer(Modifier.width(12.dp))
            Text(text = "›", fontSize = 20.sp, color = PCloudColors.TextTertiary)
        }
    }
}

/**
 * How long to keep asking for focus after a folder loads.
 *
 * `FocusRequester.requestFocus()` throws until its node is attached, and a lazy
 * container attaches its first child a frame or two after composition. 20 × 50 ms
 * is a second of grace, which is far longer than it ever takes and still invisible
 * if the item never arrives.
 */
private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 50L

/** A grid item paired with the key the lazy grid will index it by. */
private class KeyedItem(val key: String, val item: ItemMetadata)

/**
 * Pairs each item with a key that is unique within the list.
 *
 * [ItemMetadata.stableKey] is unique whenever pCloud sends `id` (or at least the
 * numeric folderid/fileid), but nothing in the response guarantees that, and a
 * duplicate is fatal rather than cosmetic: LazyVerticalGrid throws as soon as it
 * measures the second item with the same key. Suffixing the index on the repeats
 * keeps the first occurrence's key — and so its scroll/focus identity — stable.
 */
private fun uniqueKeys(items: List<ItemMetadata>): List<KeyedItem> {
    val seen = HashSet<String>(items.size)
    return items.mapIndexed { index, item ->
        var key = item.stableKey
        if (!seen.add(key)) {
            key = "$key#$index"
            // Astronomically unlikely, but a name-derived key could still collide.
            while (!seen.add(key)) key = "$key'"
        }
        KeyedItem(key, item)
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun Spinner() {
    CircularProgressIndicator(color = PCloudColors.Accent, strokeWidth = 3.dp)
}

/**
 * Focusable button with a visible focus state. Compose Material3's Button renders
 * its focus state too subtly for a ten-foot interface.
 */
@Composable
internal fun TvButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) PCloudColors.Accent.copy(alpha = 0.22f) else PCloudColors.SurfaceVariant)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) PCloudColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.85f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** The small grey line under an item's name, shared by the card and the row. */
private fun ItemMetadata.detailLine(): String = when (fileType) {
    PCloudFileType.FOLDER -> "Folder"
    PCloudFileType.AUDIO -> audioDetailLine() ?: formatFileSize(size)
    PCloudFileType.VIDEO -> videoDetailLine() ?: formatFileSize(size)
    PCloudFileType.SUBTITLE -> "Subtitles"
    PCloudFileType.IMAGE ->
        if (width != null && height != null) "${width}x$height" else formatFileSize(size)

    else -> formatFileSize(size)
}

private fun iconFor(type: PCloudFileType): Pair<ImageVector, Color> = when (type) {
    PCloudFileType.FOLDER -> Icons.Default.Folder to PCloudColors.Folder
    PCloudFileType.VIDEO -> Icons.Default.Movie to PCloudColors.Video
    PCloudFileType.IMAGE -> Icons.Default.Image to PCloudColors.Image
    PCloudFileType.AUDIO -> Icons.Default.AudioFile to PCloudColors.Audio
    PCloudFileType.DOCUMENT -> Icons.Default.Description to PCloudColors.Document
    PCloudFileType.ARCHIVE -> Icons.Default.Archive to PCloudColors.Archive
    PCloudFileType.SUBTITLE -> Icons.Default.Subtitles to PCloudColors.Subtitle
    PCloudFileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile to PCloudColors.Other
}
