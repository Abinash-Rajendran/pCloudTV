package com.ar.pcloud.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-to-scan maths behind the players' left/right keys.
 *
 * These are the only parts of the seek path that can be tested off-device: the rest
 * is ExoPlayer and Compose focus. They are worth pinning anyway, because both the
 * acceleration curve and the TIME_UNSET guard are easy to break silently — a wrong
 * clamp shows up as a seek that quietly refuses to move.
 */
class SeekTest {

    @Test
    fun `a single press is the familiar ten seconds`() {
        assertEquals(10_000L, seekStepFor(repeatCount = 0))
    }

    @Test
    fun `holding the button grows the stride, monotonically`() {
        val steps = (0..30).map { seekStepFor(it) }
        steps.zipWithNext { previous, next ->
            assertTrue("steps must never shrink while held", next >= previous)
        }
        assertTrue("a long hold must reach minute-plus strides", steps.last() >= 60_000L)
    }

    @Test
    fun `seeking back past the start lands on zero, not a negative position`() {
        assertEquals(0L, seekTarget(from = 5_000L, byMs = -30_000L, durationMs = 600_000L))
    }

    @Test
    fun `seeking past the end stops at the end`() {
        assertEquals(600_000L, seekTarget(from = 590_000L, byMs = 60_000L, durationMs = 600_000L))
    }

    @Test
    fun `an unknown duration does not clamp the target to zero`() {
        // ExoPlayer reports TIME_UNSET (Long.MIN_VALUE + 1) until the stream is ready.
        // Clamping to it would pin every forward seek at the start of the file.
        val unset = Long.MIN_VALUE + 1
        assertEquals(40_000L, seekTarget(from = 10_000L, byMs = 30_000L, durationMs = unset))
        assertEquals(40_000L, seekTarget(from = 10_000L, byMs = 30_000L, durationMs = -1L))
    }

    @Test
    fun `a chain of held presses accumulates from the pending target`() {
        var pending = 0L
        repeat(10) { press -> pending = seekTarget(pending, seekStepFor(press), 3_600_000L) }
        assertTrue("ten held presses should cross several minutes", pending > 120_000L)
    }

    @Test
    fun `speeds are labelled the way a viewer writes them`() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("0.75x", formatSpeed(0.75f))
        assertEquals("2x", formatSpeed(2f))
    }

    @Test
    fun `a label is used only when it tells the track apart from the others`() {
        // The real case: a ripper stamped its site name on all four audio tracks.
        val names = trackDisplayNames(
            listOf(
                TrackNaming(label = "www.TamilRockers.im", language = "ta"),
                TrackNaming(label = "www.TamilRockers.im", language = "te"),
                TrackNaming(label = "www.TamilRockers.im", language = "hi"),
                TrackNaming(label = "www.TamilRockers.im", language = "en")
            )
        )
        assertEquals(listOf("Tamil", "Telugu", "Hindi", "English"), names)
    }

    @Test
    fun `a distinct label still wins over the language`() {
        val names = trackDisplayNames(
            listOf(
                TrackNaming(label = "Director's commentary", language = "en"),
                TrackNaming(label = null, language = "en")
            )
        )
        assertEquals(listOf("Director's commentary", "English"), names)
    }

    @Test
    fun `two tracks in one language are told apart by their channel layout`() {
        val names = trackDisplayNames(
            listOf(
                TrackNaming(label = null, language = "en", channelCount = 2),
                TrackNaming(label = null, language = "en", channelCount = 6)
            )
        )
        assertEquals(listOf("English • Stereo", "English • 5.1"), names)
    }

    @Test
    fun `tracks with nothing to tell them apart stay separately numbered`() {
        val anonymous = TrackNaming(label = null, language = null)
        assertEquals(listOf("Track 1", "Track 2"), trackDisplayNames(listOf(anonymous, anonymous)))

        val identical = TrackNaming(label = "Audio", language = "en", channelCount = 2)
        assertEquals(
            listOf("English (1)", "English (2)"),
            trackDisplayNames(listOf(identical, identical))
        )
    }

    @Test
    fun `every offered speed snaps to itself, and a stray value to its neighbour`() {
        // The settings panel focuses the row matching the current speed; a miss here
        // would open the panel with nothing focused and the D-pad doing nothing.
        PLAYBACK_SPEEDS.forEach { assertEquals(it, nearestOfferedSpeed(it)) }
        assertEquals(1.5f, nearestOfferedSpeed(1.49f))
        assertEquals(2f, nearestOfferedSpeed(3f))
        assertEquals(0.75f, nearestOfferedSpeed(0.1f))
    }

    @Test
    fun `the speed list is ordered, contains normal, and stops at 2x`() {
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
        assertTrue("1x must be offered as a way back", PLAYBACK_SPEEDS.contains(1f))
        // Past 2x ExoPlayer's time-stretch makes speech unintelligible.
        assertEquals(2f, PLAYBACK_SPEEDS.max())
    }
}
