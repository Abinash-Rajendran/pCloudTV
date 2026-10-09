package com.ar.pcloud.data.model

/**
 * Finds the subtitle files that belong to a video, by the convention every desktop
 * player uses: the sidecar sits in the same folder and shares the video's filename.
 *
 *     Movie.mkv  <-  Movie.srt           (exact)
 *                    Movie.en.srt        (language suffix)
 *                    Movie.eng.forced.srt
 *
 * Deliberately pure Kotlin — no Android or media3 types — so the matching rules can
 * be unit-tested without a device. Mime types are kept as the plain strings media3's
 * `MimeTypes` constants hold rather than referencing that class here.
 */

/** A subtitle file that matched a video, before its stream URL has been resolved. */
data class SubtitleTrack(
    val fileId: Long,
    /** Shown in the player's track picker: "English", "English (forced)", "Subtitles". */
    val label: String,
    /** BCP-47 code when the filename carried one, else null. */
    val language: String?,
    val mimeType: String,
    /** True when the filename matched the video exactly, with no suffix. */
    val isExactMatch: Boolean
)

/**
 * Subtitle files in [folder] that belong to [video], best candidate first.
 *
 * "Best" means: an exact filename match, then English, then everything else
 * alphabetically. Callers enable the first entry by default.
 */
fun findSubtitlesFor(video: ItemMetadata, folder: List<ItemMetadata>): List<SubtitleTrack> {
    if (video.fileType != PCloudFileType.VIDEO) return emptyList()
    val stem = video.baseName
    if (stem.isEmpty()) return emptyList()

    return folder.asSequence()
        .filter { it.fileType == PCloudFileType.SUBTITLE && it.fileid != null }
        .mapNotNull { candidate -> match(stem, candidate) }
        .sortedWith(
            compareByDescending<SubtitleTrack> { it.isExactMatch }
                .thenByDescending { it.language == ENGLISH }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
        .toList()
}

private fun match(videoStem: String, candidate: ItemMetadata): SubtitleTrack? {
    val mime = MIME_BY_EXTENSION[candidate.extension] ?: return null
    val subtitleStem = candidate.baseName

    val suffix = when {
        subtitleStem.equals(videoStem, ignoreCase = true) -> ""

        // Only split on a dot boundary: "Movie 2.srt" must not match "Movie".
        subtitleStem.length > videoStem.length &&
            subtitleStem.regionMatches(0, videoStem, 0, videoStem.length, ignoreCase = true) &&
            subtitleStem[videoStem.length] == '.' -> subtitleStem.substring(videoStem.length + 1)

        else -> return null
    }

    val (language, label) = describe(suffix)
    return SubtitleTrack(
        fileId = candidate.fileid ?: return null,
        label = label,
        language = language,
        mimeType = mime,
        isExactMatch = suffix.isEmpty()
    )
}

/**
 * Turns the part of the filename after the video's name into a language code and a
 * human label: "en" -> English, "eng.forced" -> English (forced), "" -> Subtitles.
 * Anything unrecognised is shown verbatim, which beats hiding it.
 */
private fun describe(suffix: String): Pair<String?, String> {
    if (suffix.isBlank()) return null to "Subtitles"

    val parts = suffix.split('.', '-', '_').filter { it.isNotBlank() }
    if (parts.isEmpty()) return null to "Subtitles"

    val head = parts.first().lowercase()
    val language = LANGUAGE_NAMES[head]
    val qualifiers = parts.drop(if (language != null) 1 else 0)
        .mapNotNull { QUALIFIERS[it.lowercase()] }

    val name = language ?: suffix
    val label = if (qualifiers.isEmpty()) name else "$name (${qualifiers.joinToString(", ")})"
    return (if (language != null) BCP47[head] else null) to label
}

private const val ENGLISH = "en"

private val MIME_BY_EXTENSION = mapOf(
    // The literal values of androidx.media3.common.MimeTypes constants.
    "srt" to "application/x-subrip",
    "vtt" to "text/vtt",
    "ass" to "text/x-ssa",
    "ssa" to "text/x-ssa",
    "ttml" to "application/ttml+xml",
    "dfxp" to "application/ttml+xml"
)

/** Markers that describe a track rather than name its language. */
private val QUALIFIERS = mapOf(
    "forced" to "forced",
    "sdh" to "SDH",
    "cc" to "CC",
    "hi" to "HI"
)

/**
 * The languages worth naming on screen, under both their ISO 639-1 and 639-2 codes.
 * An unlisted code falls through to showing the raw suffix.
 */
private val LANGUAGE_NAMES = mapOf(
    "en" to "English", "eng" to "English",
    "hi" to "Hindi", "hin" to "Hindi",
    "ta" to "Tamil", "tam" to "Tamil",
    "te" to "Telugu", "tel" to "Telugu",
    "ml" to "Malayalam", "mal" to "Malayalam",
    "kn" to "Kannada", "kan" to "Kannada",
    "bn" to "Bengali", "ben" to "Bengali",
    "mr" to "Marathi", "mar" to "Marathi",
    "es" to "Spanish", "spa" to "Spanish",
    "fr" to "French", "fre" to "French", "fra" to "French",
    "de" to "German", "ger" to "German", "deu" to "German",
    "it" to "Italian", "ita" to "Italian",
    "pt" to "Portuguese", "por" to "Portuguese",
    "ru" to "Russian", "rus" to "Russian",
    "ja" to "Japanese", "jpn" to "Japanese",
    "ko" to "Korean", "kor" to "Korean",
    "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
    "ar" to "Arabic", "ara" to "Arabic",
    "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
    "pl" to "Polish", "pol" to "Polish",
    "tr" to "Turkish", "tur" to "Turkish",
    "sv" to "Swedish", "swe" to "Swedish",
    "da" to "Danish", "dan" to "Danish",
    "no" to "Norwegian", "nor" to "Norwegian",
    "fi" to "Finnish", "fin" to "Finnish",
    "id" to "Indonesian", "ind" to "Indonesian",
    "th" to "Thai", "tha" to "Thai",
    "vi" to "Vietnamese", "vie" to "Vietnamese"
)

/**
 * The 2-letter form of each recognised code, which is what
 * `MediaItem.SubtitleConfiguration.setLanguage` wants.
 *
 * "hi" is both ISO 639-1 Hindi and the common "hearing impaired" marker; as the
 * *leading* segment it is read as the language, which is the overwhelmingly more
 * likely intent for a file called `Movie.hi.srt`.
 */
private val BCP47: Map<String, String> = buildMap {
    val twoLetterByName = LANGUAGE_NAMES.entries
        .filter { it.key.length == 2 }
        .associate { it.value to it.key }
    LANGUAGE_NAMES.forEach { (code, name) ->
        twoLetterByName[name]?.let { put(code, it) }
    }
}
