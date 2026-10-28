package app.omnitv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackDiagnosticsTest {

    private fun createDummyEventTime(): AnalyticsListener.EventTime {
        return AnalyticsListener.EventTime(
            0L,
            androidx.media3.common.Timeline.EMPTY,
            0,
            null,
            0L,
            androidx.media3.common.Timeline.EMPTY,
            0,
            null,
            0L,
            0L
        )
    }

    @Test
    fun testBufferingAccounting() {
        val diag = PlaybackDiagnostics()
        diag.startSession()

        val eventTime = createDummyEventTime()

        // Transition to buffering
        diag.onPlaybackStateChanged(eventTime, Player.STATE_BUFFERING)
        assertEquals(1, diag.rebufferCount)

        // Sleep briefly to simulate buffer duration
        Thread.sleep(20)

        // Transition to ready
        diag.onPlaybackStateChanged(eventTime, Player.STATE_READY)
        assertTrue(diag.totalBufferingTimeMs >= 15)
        assertTrue(diag.startupTimeMs >= 15)

        // Second rebuffer event
        diag.onPlaybackStateChanged(eventTime, Player.STATE_BUFFERING)
        assertEquals(2, diag.rebufferCount)
    }

    @Test
    fun testFormatChangeTracking() {
        val diag = PlaybackDiagnostics()
        diag.startSession()

        val eventTime = createDummyEventTime()

        // Downstream format change
        val videoFormat = Format.Builder()
            .setWidth(1280)
            .setHeight(720)
            .setAverageBitrate(2500000)
            .build()
        val formatLoadData = MediaLoadData(
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_VIDEO,
            videoFormat,
            C.SELECTION_REASON_ADAPTIVE,
            null,
            0L,
            0L
        )

        diag.onDownstreamFormatChanged(eventTime, formatLoadData)
        assertEquals(2500000, diag.currentBitrateBps)
    }

    @Test
    fun testSessionReset() {
        val diag = PlaybackDiagnostics()
        diag.startSession()

        val eventTime = createDummyEventTime()
        diag.onPlaybackStateChanged(eventTime, Player.STATE_BUFFERING)
        assertEquals(1, diag.rebufferCount)

        diag.startSession()
        assertEquals(0, diag.rebufferCount)
        assertEquals(0L, diag.totalBufferingTimeMs)
        assertEquals(0L, diag.startupTimeMs)
    }
}
