package com.ar.pcloud.data.repository

import com.ar.pcloud.data.model.MediaLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * pCloud media links are time-limited — every link response carries an `expires`
 * RFC-1123 timestamp ("Thu, 03 Oct 2013 01:17:11 +0000"). Without honouring it, a
 * link resolved at the start of a long browse session is dead by the time the user
 * presses play.
 *
 * This caches resolved links keyed by (fileid, kind) and evicts them as they go
 * stale, so repeated plays of the same file in one sitting do not re-hit the API.
 */
class LinkCache(private val maxEntries: Int = 128) {

    enum class Kind { FILE, HLS, AUDIO, THUMB }

    private data class Key(val fileId: Long, val kind: Kind, val variant: String?)

    private val entries = LinkedHashMap<Key, MediaLink>(16, 0.75f, true)

    @Synchronized
    fun get(fileId: Long, kind: Kind, variant: String? = null): MediaLink? {
        val key = Key(fileId, kind, variant)
        val cached = entries[key] ?: return null
        if (!cached.isFresh()) {
            entries.remove(key)
            return null
        }
        return cached
    }

    @Synchronized
    fun put(fileId: Long, kind: Kind, link: MediaLink, variant: String? = null) {
        entries[Key(fileId, kind, variant)] = link
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /**
         * Parses the `expires` field. The docs show RFC-1123 with a numeric offset;
         * the zone-name form is accepted too. Returns 0 when absent or unparseable,
         * which [MediaLink.isFresh] treats as "no stated expiry".
         */
        fun parseExpires(expires: String?): Long {
            if (expires.isNullOrBlank()) return 0L
            for (pattern in EXPIRES_PATTERNS) {
                try {
                    val format = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                        isLenient = true
                    }
                    val parsed: Date = format.parse(expires) ?: continue
                    return parsed.time
                } catch (_: Exception) {
                    // try the next pattern
                }
            }
            return 0L
        }

        private val EXPIRES_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z"
        )
    }
}
