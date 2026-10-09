package com.ar.pcloud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * TV apps are always dark — there is no light mode worth having on a television,
 * and following the system theme would let a bright white grid blast a dark room.
 */
private val PCloudDarkScheme = darkColorScheme(
    primary = PCloudColors.Accent,
    onPrimary = PCloudColors.Background,
    secondary = PCloudColors.AccentMuted,
    onSecondary = PCloudColors.TextPrimary,
    background = PCloudColors.Background,
    onBackground = PCloudColors.TextPrimary,
    surface = PCloudColors.Surface,
    onSurface = PCloudColors.TextPrimary,
    surfaceVariant = PCloudColors.SurfaceVariant,
    onSurfaceVariant = PCloudColors.TextSecondary,
    error = PCloudColors.Error,
    onError = PCloudColors.Background
)

@Composable
fun PcloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PCloudDarkScheme,
        typography = Typography,
        content = content
    )
}
