package com.ar.pcloud.data

import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.data.repository.LinkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCacheExpiresParsingTest {

    /** The format the docs show: "Thu, 03 Oct 2013 01:17:11 +0000". */
    @Test
    fun `parses the documented rfc1123 format`() {
        val parsed = LinkCache.parseExpires("Thu, 03 Oct 2013 01:17:11 +0000")
        assertEquals(1380763031000L, parsed)
    }

    @Test
    fun `parses the zone-name variant`() {
        val parsed = LinkCache.parseExpires("Thu, 03 Oct 2013 01:17:11 GMT")
        assertEquals(1380763031000L, parsed)
    }

    @Test
    fun `parses a single-digit day`() {
        assertEquals(true, LinkCache.parseExpires("Tue, 1 Oct 2013 01:17:11 +0000") > 0L)
    }

    /** 0 means "no stated expiry", which MediaLink treats as always fresh. */
    @Test
    fun `unparseable or missing expires yields zero`() {
        assertEquals(0L, LinkCache.parseExpires(null))
        assertEquals(0L, LinkCache.parseExpires(""))
        assertEquals(0L, LinkCache.parseExpires("   "))
        assertEquals(0L, LinkCache.parseExpires("not a date at all"))
    }
}

class LinkCacheBehaviourTest {

    private fun freshLink(url: String) = MediaLink(
        url = url,
        expiresAtMillis = System.currentTimeMillis() + 10 * MediaLink.FRESHNESS_MARGIN_MS
    )

    @Test
    fun `stores and returns a fresh link`() {
        val cache = LinkCache()
        cache.put(1L, LinkCache.Kind.FILE, freshLink("https://c1/a"))
        assertEquals("https://c1/a", cache.get(1L, LinkCache.Kind.FILE)?.url)
    }

    @Test
    fun `misses on an unknown file`() {
        assertNull(LinkCache().get(404L, LinkCache.Kind.FILE))
    }

    /** The same file has different links per endpoint; they must not collide. */
    @Test
    fun `kinds are kept separate`() {
        val cache = LinkCache()
        cache.put(1L, LinkCache.Kind.FILE, freshLink("https://c1/original.mkv"))
        cache.put(1L, LinkCache.Kind.HLS, freshLink("https://c1/stream.m3u8"))
        cache.put(1L, LinkCache.Kind.AUDIO, freshLink("https://c1/audio.mp3"))

        assertEquals("https://c1/original.mkv", cache.get(1L, LinkCache.Kind.FILE)?.url)
        assertEquals("https://c1/stream.m3u8", cache.get(1L, LinkCache.Kind.HLS)?.url)
        assertEquals("https://c1/audio.mp3", cache.get(1L, LinkCache.Kind.AUDIO)?.url)
        assertNull(cache.get(1L, LinkCache.Kind.THUMB))
    }

    /** The whole point: a link that has aged out must not be handed back. */
    @Test
    fun `evicts a stale link on read`() {
        val cache = LinkCache()
        val stale = MediaLink(url = "https://c1/old", expiresAtMillis = 1L)
        cache.put(2L, LinkCache.Kind.FILE, stale)
        assertNull(cache.get(2L, LinkCache.Kind.FILE))
    }

    @Test
    fun `evicts the least recently used entry past capacity`() {
        val cache = LinkCache(maxEntries = 2)
        cache.put(1L, LinkCache.Kind.FILE, freshLink("https://c1/1"))
        cache.put(2L, LinkCache.Kind.FILE, freshLink("https://c1/2"))

        // Touch 1 so 2 becomes the least recently used.
        assertNotNull(cache.get(1L, LinkCache.Kind.FILE))

        cache.put(3L, LinkCache.Kind.FILE, freshLink("https://c1/3"))

        assertNotNull(cache.get(1L, LinkCache.Kind.FILE))
        assertNull(cache.get(2L, LinkCache.Kind.FILE))
        assertNotNull(cache.get(3L, LinkCache.Kind.FILE))
    }

    /** Signing out must not leave another account's media links behind. */
    @Test
    fun `clear drops everything`() {
        val cache = LinkCache()
        cache.put(1L, LinkCache.Kind.FILE, freshLink("https://c1/1"))
        cache.clear()
        assertNull(cache.get(1L, LinkCache.Kind.FILE))
    }
}
