package com.ar.pcloud.ui

import com.ar.pcloud.data.model.ItemMetadata
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024) return "$bytes B"
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(5)
    val unit = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), unit)
}

/** mm:ss, or h:mm:ss past an hour. Negative/unknown values render as "--:--". */
fun formatDuration(millis: Long): String {
    if (millis < 0) return "--:--"
    val total = millis.milliseconds.inWholeSeconds
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

/** The API sends video duration as a float-in-a-string; may be absent. */
fun ItemMetadata.formattedDuration(): String? =
    durationSeconds?.let { formatDuration(it.seconds.inWholeMilliseconds) }

/**
 * "Queen — A Night at the Opera", skipping whichever tag is missing.
 *
 * Named *detail line*, not subtitle: this is the small grey line under a card
 * title, and the app now also deals in real captions.
 */
fun ItemMetadata.audioDetailLine(): String? {
    val parts = listOfNotNull(
        artist?.takeIf { it.isNotBlank() },
        album?.takeIf { it.isNotBlank() },
        // Video rows have carried their running time all along; music rows showing
        // only a file size was an oversight, not a decision.
        formattedDuration()
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

/** Short technical line for a video card: "1920x1080 • h264 • 1:42:30". */
fun ItemMetadata.videoDetailLine(): String? {
    val parts = listOfNotNull(
        if (width != null && height != null) "${width}x$height" else null,
        videocodec?.takeIf { it.isNotBlank() },
        formattedDuration()
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}
