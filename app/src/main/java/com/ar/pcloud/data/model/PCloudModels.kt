package com.ar.pcloud.data.model

import com.google.gson.annotations.SerializedName

/**
 * pCloud runs two independent data centers; an account lives in exactly one and its
 * auth token is only valid on that host. See docs/PCLOUD_API.md section 1.
 */
enum class ServerRegion(val displayName: String, val baseUrl: String, val locationId: Int) {
    US("United States (api.pcloud.com)", "https://api.pcloud.com/", 1),
    EU("Europe (eapi.pcloud.com)", "https://eapi.pcloud.com/", 2);

    companion object {
        fun fromLocationId(locationId: Int?): ServerRegion? =
            entries.firstOrNull { it.locationId == locationId }

        fun fromHostname(hostname: String?): ServerRegion? = when {
            hostname.isNullOrBlank() -> null
            hostname.startsWith("eapi") -> EU
            hostname.startsWith("api") -> US
            else -> null
        }
    }
}

/** Every pCloud response carries `result`; 0 means success. */
interface PCloudResponse {
    val result: Int
    val error: String?
}

data class DigestResponse(
    @SerializedName("result") override val result: Int,
    @SerializedName("digest") val digest: String?,
    @SerializedName("expires") val expires: String? = null,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse

data class UserInfoResponse(
    @SerializedName("result") override val result: Int,
    @SerializedName("auth") val auth: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("userid") val userid: Long? = null,
    @SerializedName("quota") val quota: Long? = null,
    @SerializedName("usedquota") val usedquota: Long? = null,
    /** 1 = US, 2 = EU. Tells us which host the account actually belongs to. */
    @SerializedName("locationid") val locationid: Int? = null,
    @SerializedName("apiserver") val apiserver: String? = null,
    /**
     * Handed back by `login` instead of `auth` when the account has two-factor
     * enabled. It stands for the half-finished login and is the required input
     * to `tfa_login`.
     */
    @SerializedName("token") val tfaToken: String? = null,
    /** True on a `login` reply that needs a 2FA code to continue. */
    @SerializedName("tfa") val tfa: Boolean = false,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse

data class FolderResponse(
    @SerializedName("result") override val result: Int,
    @SerializedName("metadata") val metadata: ItemMetadata? = null,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse

/**
 * The pCloud metadata structure (docs/PCLOUD_API.md section 4). One type covers both
 * folders and files; `isfolder` discriminates. Media fields are only present for the
 * matching category, and `duration`/`fps` arrive as *strings* per the docs.
 */
data class ItemMetadata(
    @SerializedName("name") val name: String = "",
    @SerializedName("isfolder") val isfolder: Boolean = false,
    @SerializedName("folderid") val folderid: Long? = null,
    @SerializedName("fileid") val fileid: Long? = null,
    @SerializedName("parentfolderid") val parentfolderid: Long? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("created") val created: String? = null,
    @SerializedName("modified") val modified: String? = null,
    @SerializedName("size") val size: Long = 0,
    @SerializedName("contenttype") val contenttype: String? = null,
    /**
     * Content hash, kept as a string.
     *
     * The docs call this a 64-bit number, but pCloud sends it *unsigned*, so
     * values above 2^63-1 (e.g. 14308384023257526307) overflow a Kotlin `Long`
     * and Gson aborts the whole response with NumberFormatException — one such
     * file made its entire folder unopenable. Nothing here does arithmetic on
     * it; it is only ever an identity/cache key, so text is the honest type.
     */
    @SerializedName("hash") val hash: String? = null,
    /** 0 uncategorized, 1 image, 2 video, 3 audio, 4 document, 5 archive. */
    @SerializedName("category") val category: Int? = null,
    /** "true if thumbs can be created from the object" */
    @SerializedName("thumb") val thumb: Boolean = false,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("isshared") val isshared: Boolean = false,
    @SerializedName("ismine") val ismine: Boolean = true,
    @SerializedName("isdeleted") val isdeleted: Boolean = false,
    @SerializedName("contents") val contents: List<ItemMetadata>? = null,

    // Image + video
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,

    // Video (duration and fps are floats sent as strings)
    @SerializedName("duration") val duration: String? = null,
    @SerializedName("fps") val fps: String? = null,
    @SerializedName("videocodec") val videocodec: String? = null,
    @SerializedName("audiocodec") val audiocodec: String? = null,
    @SerializedName("videobitrate") val videobitrate: Int? = null,
    @SerializedName("audiobitrate") val audiobitrate: Int? = null,
    @SerializedName("audiosamplerate") val audiosamplerate: Int? = null,
    @SerializedName("rotate") val rotate: Int? = null,

    // Audio tags
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("album") val album: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("trackno") val trackno: String? = null
) {
    val fileType: PCloudFileType get() = PCloudFileType.from(this)

    /** Lowercased extension without the dot, or "" when the name has none. */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** The filename with its extension removed — the key for sidecar matching. */
    val baseName: String get() = name.substringBeforeLast('.', name)

    /** Seconds, or null. The API sends this as a string like "212.456". */
    val durationSeconds: Double? get() = duration?.toDoubleOrNull()

    /** Best display title for an audio track: ID3 title, else the filename. */
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name

    /** Stable key for lists and caches. `id` is unique; fall back to the numeric ids. */
    val stableKey: String
        get() = id ?: if (isfolder) "d${folderid ?: 0}" else "f${fileid ?: 0}"
}

/**
 * Typed category. `category` from the API is authoritative; MIME and extension are
 * only consulted when the server left it uncategorized (category 0 / absent), which
 * happens for files uploaded with a generic content type.
 */
enum class PCloudFileType {
    FOLDER, VIDEO, IMAGE, AUDIO, DOCUMENT, ARCHIVE, SUBTITLE, OTHER;

    val isPlayableMedia: Boolean get() = this == VIDEO || this == AUDIO
    val isViewable: Boolean get() = isPlayableMedia || this == IMAGE

    companion object {
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv", "wmv",
            "mpg", "mpeg", "m2ts", "mts", "3gp", "ogv", "divx", "vob", "rmvb"
        )
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
            "tif", "tiff", "dng", "cr2", "nef", "arw", "svg"
        )
        private val AUDIO_EXT = setOf(
            "mp3", "flac", "wav", "aac", "ogg", "oga", "m4a", "m4b", "opus",
            "wma", "alac", "aiff", "ape", "dsf", "mka"
        )
        private val DOCUMENT_EXT = setOf(
            "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf", "epub", "md", "csv"
        )
        private val ARCHIVE_EXT = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"
        )

        /**
         * Text subtitle formats ExoPlayer can side-load next to a video.
         *
         * Bitmap formats (.sub/.idx VobSub, .sup PGS) are deliberately absent:
         * they cannot be attached as a MediaItem subtitle configuration, so
         * recognising them would only produce a track that never renders.
         */
        internal val SUBTITLE_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

        /** pCloud `category` values, per docs/PCLOUD_API.md section 4. */
        private const val CATEGORY_UNCATEGORIZED = 0
        private const val CATEGORY_IMAGE = 1
        private const val CATEGORY_VIDEO = 2
        private const val CATEGORY_AUDIO = 3
        private const val CATEGORY_DOCUMENT = 4
        private const val CATEGORY_ARCHIVE = 5

        fun from(item: ItemMetadata): PCloudFileType {
            if (item.isfolder) return FOLDER

            // pCloud has no subtitle category — a .srt comes back as uncategorized
            // or, worse, as a document, which would hide it behind the wrong icon.
            // The extension is the only signal there is, so it is consulted first.
            if (item.extension in SUBTITLE_EXT) return SUBTITLE

            when (item.category) {
                CATEGORY_IMAGE -> return IMAGE
                CATEGORY_VIDEO -> return VIDEO
                CATEGORY_AUDIO -> return AUDIO
                CATEGORY_DOCUMENT -> return DOCUMENT
                CATEGORY_ARCHIVE -> return ARCHIVE
                // 0 or null -> fall through and infer
                CATEGORY_UNCATEGORIZED, null -> Unit
            }

            // The server's own icon hint is the next best signal.
            when (item.icon?.lowercase()) {
                "video" -> return VIDEO
                "image" -> return IMAGE
                "audio" -> return AUDIO
                "document", "spreadsheet", "presentation", "web" -> return DOCUMENT
                "archive", "diskimage" -> return ARCHIVE
            }

            val mime = item.contenttype?.lowercase().orEmpty()
            when {
                mime.startsWith("video/") -> return VIDEO
                mime.startsWith("image/") -> return IMAGE
                mime.startsWith("audio/") -> return AUDIO
            }

            return when (item.extension) {
                in VIDEO_EXT -> VIDEO
                in IMAGE_EXT -> IMAGE
                in AUDIO_EXT -> AUDIO
                in DOCUMENT_EXT -> DOCUMENT
                in ARCHIVE_EXT -> ARCHIVE
                else -> OTHER
            }
        }
    }
}

/**
 * Shared shape of getfilelink / gethlslink / getaudiolink / getthumblink.
 *
 * The docs: "You need to construct the URL yourself by concatenating http:// or
 * https:// with one of the hosts (first one) and the path." `path` is already
 * percent-encoded, so it is concatenated raw.
 */
data class FileLinkResponse(
    @SerializedName("result") override val result: Int,
    @SerializedName("hosts") val hosts: List<String>? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("expires") val expires: String? = null,
    /** getthumblink/getthumbslinks only: the *actual* produced size. */
    @SerializedName("size") val thumbSize: String? = null,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse {

    /** All candidate URLs, best host first, for fallback when a host misbehaves. */
    val urls: List<String>
        get() {
            val p = path ?: return emptyList()
            return hosts.orEmpty().map { "https://$it$p" }
        }

    val downloadUrl: String? get() = urls.firstOrNull()
}

/** One entry of `getthumbslinks`' `thumbs` array. Each carries its own `result`. */
data class ThumbEntry(
    @SerializedName("result") override val result: Int,
    @SerializedName("fileid") val fileid: Long? = null,
    @SerializedName("hosts") val hosts: List<String>? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("expires") val expires: String? = null,
    @SerializedName("size") val size: String? = null,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse {
    val url: String?
        get() {
            val host = hosts?.firstOrNull() ?: return null
            val p = path ?: return null
            return "https://$host$p"
        }
}

data class ThumbsLinksResponse(
    @SerializedName("result") override val result: Int,
    @SerializedName("thumbs") val thumbs: List<ThumbEntry>? = null,
    @SerializedName("error") override val error: String? = null
) : PCloudResponse

data class Breadcrumb(
    val folderId: Long,
    val name: String
)

/** A resolved, time-limited media link. */
data class MediaLink(
    val url: String,
    val fallbackUrls: List<String> = emptyList(),
    val expiresAtMillis: Long = 0L,
    val isHls: Boolean = false
) {
    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis == 0L || nowMillis < expiresAtMillis - FRESHNESS_MARGIN_MS

    companion object {
        /** Re-request this far before the stated expiry so playback never starts on a dead link. */
        const val FRESHNESS_MARGIN_MS = 60_000L
    }
}
