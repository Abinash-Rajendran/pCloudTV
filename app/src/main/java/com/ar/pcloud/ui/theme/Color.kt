package com.ar.pcloud.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dark slate palette, tuned for a living-room screen: the background stays near
 * black so a bright TV in a dark room is not painful, and focus uses a single
 * saturated cyan so the selected item is unmistakable from across the room.
 */
object PCloudColors {
    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF151F33)
    val SurfaceVariant = Color(0xFF1E293B)
    val SurfaceFocused = Color(0xFF24344F)

    val Accent = Color(0xFF38BDF8)
    val AccentMuted = Color(0xFF0E7490)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextTertiary = Color(0xFF64748B)

    val Error = Color(0xFFF87171)

    // Per file category, so a glance at the grid tells you what is in a folder.
    val Folder = Color(0xFFF5B942)
    val Video = Color(0xFFF87171)
    val Image = Color(0xFF34D399)
    val Audio = Color(0xFFC084FC)
    val Document = Color(0xFF60A5FA)
    val Archive = Color(0xFF94A3B8)
    val Subtitle = Color(0xFFFBBF24)
    val Other = Color(0xFF64748B)

    val Scrim = Color(0xE60B1220)
}

// Retained so the generated theme scaffolding keeps compiling.
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
