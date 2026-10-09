package com.ar.pcloud.data

import com.ar.pcloud.data.model.FileLinkResponse
import com.ar.pcloud.data.model.FolderResponse
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.data.model.PCloudFileType
import com.ar.pcloud.data.model.ServerRegion
import com.ar.pcloud.data.model.ThumbEntry
import com.ar.pcloud.data.model.findSubtitlesFor
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PCloudFileTypeTest {

    private fun file(
        name: String = "x",
        category: Int? = null,
        contentType: String? = null,
        icon: String? = null
    ) = ItemMetadata(
        name = name,
        isfolder = false,
        fileid = 1L,
        category = category,
        contenttype = contentType,
        icon = icon
    )

    @Test
    fun `folders are folders regardless of other fields`() {
        val folder = ItemMetadata(name = "Movies", isfolder = true, folderid = 7L, category = 2)
        assertEquals(PCloudFileType.FOLDER, folder.fileType)
    }

    /** category is the API's own classification, so it wins over any guess. */
    @Test
    fun `category drives the type`() {
        assertEquals(PCloudFileType.IMAGE, file(category = 1).fileType)
        assertEquals(PCloudFileType.VIDEO, file(category = 2).fileType)
        assertEquals(PCloudFileType.AUDIO, file(category = 3).fileType)
        assertEquals(PCloudFileType.DOCUMENT, file(category = 4).fileType)
        assertEquals(PCloudFileType.ARCHIVE, file(category = 5).fileType)
    }

    @Test
    fun `category beats a misleading extension`() {
        val item = file(name = "holiday.txt", category = 2)
        assertEquals(PCloudFileType.VIDEO, item.fileType)
    }

    /**
     * The real-world case the old extension-only code got wrong: an .mkv served as
     * application/octet-stream with category 0.
     */
    @Test
    fun `uncategorized mkv served as octet-stream is still a video`() {
        val item = file(name = "film.mkv", category = 0, contentType = "application/octet-stream")
        assertEquals(PCloudFileType.VIDEO, item.fileType)
    }

    @Test
    fun `icon hint is used when category is absent`() {
        val item = file(name = "no-extension", category = 0, icon = "video")
        assertEquals(PCloudFileType.VIDEO, item.fileType)
    }

    @Test
    fun `mime type is used when category and icon are absent`() {
        val item = file(name = "clip", contentType = "video/mp4")
        assertEquals(PCloudFileType.VIDEO, item.fileType)
    }

    @Test
    fun `extension is the last resort`() {
        assertEquals(PCloudFileType.AUDIO, file(name = "song.flac").fileType)
        assertEquals(PCloudFileType.IMAGE, file(name = "pic.heic").fileType)
        assertEquals(PCloudFileType.VIDEO, file(name = "movie.m2ts").fileType)
        assertEquals(PCloudFileType.OTHER, file(name = "blob.qqq").fileType)
    }

    @Test
    fun `playable and viewable flags`() {
        assertTrue(PCloudFileType.VIDEO.isPlayableMedia)
        assertTrue(PCloudFileType.AUDIO.isPlayableMedia)
        assertFalse(PCloudFileType.IMAGE.isPlayableMedia)
        assertTrue(PCloudFileType.IMAGE.isViewable)
        assertFalse(PCloudFileType.DOCUMENT.isViewable)
    }

    @Test
    fun `displayTitle prefers the id3 title`() {
        val tagged = ItemMetadata(name = "01.mp3", fileid = 1L, title = "Bohemian Rhapsody")
        assertEquals("Bohemian Rhapsody", tagged.displayTitle)

        val untagged = ItemMetadata(name = "01.mp3", fileid = 1L, title = "  ")
        assertEquals("01.mp3", untagged.displayTitle)
    }

    @Test
    fun `duration is parsed from the string the api sends`() {
        assertEquals(212.456, ItemMetadata(name = "v", duration = "212.456").durationSeconds!!, 1e-6)
        assertNull(ItemMetadata(name = "v", duration = "n/a").durationSeconds)
        assertNull(ItemMetadata(name = "v").durationSeconds)
    }

    @Test
    fun `stableKey distinguishes files from folders`() {
        assertEquals("d5", ItemMetadata(name = "f", isfolder = true, folderid = 5L).stableKey)
        assertEquals("f9", ItemMetadata(name = "x", fileid = 9L).stableKey)
        assertEquals("d5", ItemMetadata(name = "f", isfolder = true, folderid = 5L, id = "d5").stableKey)
    }
}

class LinkConstructionTest {

    /**
     * Docs: "concatenating http:// or https:// with one of the hosts (first one)
     * and the path". The first host is "the one we consider best".
     */
    @Test
    fun `urls are built host plus path, best host first`() {
        val response = FileLinkResponse(
            result = 0,
            hosts = listOf("c11.pcloud.com", "c20.pcloud.com"),
            path = "/hash/My%20video.mp4"
        )
        assertEquals(
            listOf(
                "https://c11.pcloud.com/hash/My%20video.mp4",
                "https://c20.pcloud.com/hash/My%20video.mp4"
            ),
            response.urls
        )
        assertEquals("https://c11.pcloud.com/hash/My%20video.mp4", response.downloadUrl)
    }

    /** `path` arrives percent-encoded; re-encoding it would break the link. */
    @Test
    fun `already-encoded path is not touched`() {
        val response = FileLinkResponse(
            result = 0,
            hosts = listOf("c1.pcloud.com"),
            path = "/hash/A%20B%2BC.mp3"
        )
        assertEquals("https://c1.pcloud.com/hash/A%20B%2BC.mp3", response.downloadUrl)
    }

    @Test
    fun `missing hosts or path yields no url`() {
        assertNull(FileLinkResponse(result = 0, hosts = emptyList(), path = "/p").downloadUrl)
        assertNull(FileLinkResponse(result = 0, hosts = listOf("c1.pcloud.com")).downloadUrl)
        assertTrue(FileLinkResponse(result = 5002).urls.isEmpty())
    }

    @Test
    fun `thumb entry builds its own url`() {
        val entry = ThumbEntry(
            result = 0,
            fileid = 42L,
            hosts = listOf("c14.pcloud.com"),
            path = "/x/th-42-320x180.jpg"
        )
        assertEquals("https://c14.pcloud.com/x/th-42-320x180.jpg", entry.url)
        assertNull(ThumbEntry(result = 1014, fileid = 42L, error = "no thumb").url)
    }
}

class MediaLinkFreshnessTest {

    @Test
    fun `a link with no stated expiry is always fresh`() {
        assertTrue(MediaLink(url = "https://x/y", expiresAtMillis = 0L).isFresh())
    }

    @Test
    fun `a link past its expiry is stale`() {
        val now = 1_000_000_000L
        val link = MediaLink(url = "https://x/y", expiresAtMillis = now - 1)
        assertFalse(link.isFresh(now))
    }

    /** Re-request before the deadline so playback never starts on a dying link. */
    @Test
    fun `a link inside the safety margin is already stale`() {
        val now = 1_000_000_000L
        val link = MediaLink(
            url = "https://x/y",
            expiresAtMillis = now + MediaLink.FRESHNESS_MARGIN_MS / 2
        )
        assertFalse(link.isFresh(now))
    }

    @Test
    fun `a link well before its expiry is fresh`() {
        val now = 1_000_000_000L
        val link = MediaLink(
            url = "https://x/y",
            expiresAtMillis = now + MediaLink.FRESHNESS_MARGIN_MS * 10
        )
        assertTrue(link.isFresh(now))
    }
}

class ServerRegionTest {

    @Test
    fun `locationid maps to the right data center`() {
        assertEquals(ServerRegion.US, ServerRegion.fromLocationId(1))
        assertEquals(ServerRegion.EU, ServerRegion.fromLocationId(2))
        assertNull(ServerRegion.fromLocationId(99))
        assertNull(ServerRegion.fromLocationId(null))
    }

    @Test
    fun `hostname maps to the right data center`() {
        assertEquals(ServerRegion.US, ServerRegion.fromHostname("api.pcloud.com"))
        assertEquals(ServerRegion.EU, ServerRegion.fromHostname("eapi.pcloud.com"))
        assertNull(ServerRegion.fromHostname("example.com"))
        assertNull(ServerRegion.fromHostname(null))
    }

    @Test
    fun `base urls point at the documented hosts`() {
        assertEquals("https://api.pcloud.com/", ServerRegion.US.baseUrl)
        assertEquals("https://eapi.pcloud.com/", ServerRegion.EU.baseUrl)
    }
}

/**
 * Parses real `listfolder` JSON through Gson, the way Retrofit does.
 *
 * Every other test here builds [ItemMetadata] by hand, which is exactly how a
 * deserialization bug got as far as a TV: a file whose `hash` exceeded Long.MAX_VALUE
 * made Gson abort the whole response, so one odd file took its entire folder offline.
 */
class FolderJsonParsingTest {

    private val gson = Gson()

    /**
     * The value from the crash report. pCloud documents `hash` as a 64-bit number
     * but sends it unsigned, so 14308384023257526307 > 2^63-1 and a Long field
     * fails with "Expected a long but was ... path $.metadata.contents[5].hash".
     */
    @Test
    fun `an unsigned 64-bit hash does not break the folder`() {
        val json = """
            {
              "result": 0,
              "metadata": {
                "name": "Movies", "isfolder": true, "folderid": 12,
                "contents": [
                  {"name": "A.mkv", "isfolder": false, "fileid": 1, "hash": 123456789},
                  {"name": "B.mkv", "isfolder": false, "fileid": 2,
                   "hash": 14308384023257526307},
                  {"name": "C.mkv", "isfolder": false, "fileid": 3, "hash": 987654321}
                ]
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, FolderResponse::class.java)
        val contents = response.metadata?.contents

        assertEquals(0, response.result)
        // The point of the fix: the other files survive the one odd sibling.
        assertEquals(3, contents?.size)
        assertEquals(listOf("A.mkv", "B.mkv", "C.mkv"), contents?.map { it.name })
        // And the value itself is intact, not truncated or rounded.
        assertEquals("14308384023257526307", contents?.get(1)?.hash)
    }

    @Test
    fun `a missing hash is null`() {
        val json = """
            {"result": 0, "metadata": {"name": "x", "isfolder": true, "folderid": 1,
             "contents": [{"name": "no-hash.mp4", "isfolder": false, "fileid": 9}]}}
        """.trimIndent()

        val item = gson.fromJson(json, FolderResponse::class.java).metadata?.contents?.first()
        assertEquals("no-hash.mp4", item?.name)
        assertNull(item?.hash)
    }

    /** duration/fps are documented as floats-in-strings; category/ids stay numeric. */
    @Test
    fun `a full video entry parses with its media fields`() {
        val json = """
            {"result": 0, "metadata": {"name": "x", "isfolder": true, "folderid": 1,
             "contents": [{
               "name": "Film.mkv", "isfolder": false, "fileid": 42, "size": 1073741824,
               "category": 2, "thumb": true, "contenttype": "video/x-matroska",
               "hash": 18446744073709551615, "width": 3840, "height": 2160,
               "duration": "7245.5", "fps": "23.976", "videocodec": "hevc"
             }]}}
        """.trimIndent()

        val item = gson.fromJson(json, FolderResponse::class.java).metadata?.contents?.first()!!

        assertEquals(PCloudFileType.VIDEO, item.fileType)
        assertEquals(42L, item.fileid)
        assertEquals("18446744073709551615", item.hash)
        assertEquals(7245.5, item.durationSeconds!!, 1e-6)
        assertEquals(3840, item.width)
        assertTrue(item.thumb)
    }
}

class SubtitleMatchingTest {

    private fun video(name: String) =
        ItemMetadata(name = name, isfolder = false, fileid = 100L, category = 2)

    private fun sub(name: String, id: Long = 1L) =
        ItemMetadata(name = name, isfolder = false, fileid = id)

    @Test
    fun `subtitle files are their own type`() {
        assertEquals(PCloudFileType.SUBTITLE, sub("Movie.srt").fileType)
        assertEquals(PCloudFileType.SUBTITLE, sub("Movie.VTT").fileType)
        assertEquals(PCloudFileType.SUBTITLE, sub("Movie.ass").fileType)
    }

    /**
     * pCloud classifies .srt as a document (category 4). The extension has to win
     * or sidecars never get recognised.
     */
    @Test
    fun `extension beats a document category for subtitles`() {
        val item = ItemMetadata(name = "Movie.srt", fileid = 1L, category = 4)
        assertEquals(PCloudFileType.SUBTITLE, item.fileType)
    }

    /** Bitmap subtitles cannot be side-loaded, so they must not look like tracks. */
    @Test
    fun `bitmap subtitle formats are not treated as subtitles`() {
        assertEquals(PCloudFileType.OTHER, sub("Movie.sup").fileType)
        assertEquals(PCloudFileType.OTHER, sub("Movie.idx").fileType)
    }

    @Test
    fun `an exact name match comes first and needs no language`() {
        val tracks = findSubtitlesFor(
            video("Movie.mkv"),
            listOf(sub("Movie.en.srt", 2L), sub("Movie.srt", 3L))
        )
        assertEquals(2, tracks.size)
        assertEquals(3L, tracks.first().fileId)
        assertEquals("Subtitles", tracks.first().label)
        assertNull(tracks.first().language)
        assertTrue(tracks.first().isExactMatch)
    }

    @Test
    fun `a language suffix becomes the label and a bcp-47 code`() {
        val tracks = findSubtitlesFor(video("Movie.mkv"), listOf(sub("Movie.eng.srt")))
        assertEquals("English", tracks.single().label)
        assertEquals("en", tracks.single().language)
        assertEquals("application/x-subrip", tracks.single().mimeType)
    }

    @Test
    fun `qualifiers are kept alongside the language`() {
        val tracks = findSubtitlesFor(video("Movie.mkv"), listOf(sub("Movie.en.forced.srt")))
        assertEquals("English (forced)", tracks.single().label)
        assertEquals("en", tracks.single().language)
    }

    @Test
    fun `english sorts ahead of other languages`() {
        val tracks = findSubtitlesFor(
            video("Movie.mkv"),
            listOf(sub("Movie.ta.srt", 1L), sub("Movie.en.srt", 2L), sub("Movie.fr.srt", 3L))
        )
        assertEquals("English", tracks.first().label)
        assertEquals(listOf(2L, 3L, 1L), tracks.map { it.fileId })
    }

    @Test
    fun `matching ignores case`() {
        val tracks = findSubtitlesFor(video("Movie.MKV"), listOf(sub("MOVIE.srt")))
        assertEquals(1, tracks.size)
    }

    /** "Movie 2.srt" belongs to "Movie 2", not to "Movie" — split on dots only. */
    @Test
    fun `a longer name is not a prefix match`() {
        assertTrue(findSubtitlesFor(video("Movie.mkv"), listOf(sub("Movie 2.srt"))).isEmpty())
        assertTrue(findSubtitlesFor(video("Movie.mkv"), listOf(sub("Movie2.srt"))).isEmpty())
    }

    @Test
    fun `unrelated files are ignored`() {
        val folder = listOf(
            sub("Other.srt", 1L),
            ItemMetadata(name = "Movie.nfo", fileid = 2L),
            ItemMetadata(name = "Movie.mkv", fileid = 3L, category = 2)
        )
        assertTrue(findSubtitlesFor(video("Movie.mkv"), folder).isEmpty())
    }

    @Test
    fun `an unknown language code is shown verbatim rather than dropped`() {
        val tracks = findSubtitlesFor(video("Movie.mkv"), listOf(sub("Movie.klingon.srt")))
        assertEquals("klingon", tracks.single().label)
        assertNull(tracks.single().language)
    }

    @Test
    fun `mime type follows the extension`() {
        val tracks = findSubtitlesFor(
            video("M.mkv"),
            listOf(sub("M.vtt", 1L), sub("M.ass", 2L), sub("M.ttml", 3L))
        )
        assertEquals(
            setOf("text/vtt", "text/x-ssa", "application/ttml+xml"),
            tracks.map { it.mimeType }.toSet()
        )
    }

    @Test
    fun `nothing is matched for a non-video`() {
        val audio = ItemMetadata(name = "Song.mp3", fileid = 1L, category = 3)
        assertTrue(findSubtitlesFor(audio, listOf(sub("Song.srt"))).isEmpty())
    }
}
