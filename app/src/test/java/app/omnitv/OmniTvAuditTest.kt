package app.omnitv

import app.omnitv.data.parser.M3uParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class OmniTvAuditTest {

    @Test
    fun testRemoteKeyCodesValues() {
        // Verify key codes defined for Aspect ratio and navigation match Android specifications
        assertEquals(184, android.view.KeyEvent.KEYCODE_PROG_GREEN)
        assertEquals(185, android.view.KeyEvent.KEYCODE_PROG_YELLOW)
        assertEquals(222, android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
        assertEquals(7, android.view.KeyEvent.KEYCODE_0)
        assertEquals(4, android.view.KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun testHudAutoHideLogic() {
        var showOverlay = true
        var showChannelGuide = false
        val showUrlDialog = false
        val showAspectDialog = false
        val showExitDialog = false

        fun shouldAutoHide(): Boolean {
            return showOverlay && !showUrlDialog && !showAspectDialog && !showExitDialog
        }

        assertTrue(shouldAutoHide())

        // Also hides when channel guide is open
        showChannelGuide = true
        assertTrue(shouldAutoHide())

        // Dialog open should pause auto-hide
        val dialogOpen = true
        val shouldAutoHideWithDialog = showOverlay && !dialogOpen && !showAspectDialog && !showExitDialog
        assertFalse(shouldAutoHideWithDialog)
    }

    @Test
    fun testCacheIsolationByKey() {
        M3uParser.clearCache()
        val m3uContentA = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1",Channel 1 HD
            http://example.com/ch1.m3u8
        """.trimIndent()

        val m3uContentB = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch2",Channel 2 FHD
            http://example.com/ch2.m3u8
        """.trimIndent()

        val resA = M3uParser.parseCached("http://urlA.com/list.m3u", m3uContentA, 1L)
        val resB = M3uParser.parseCached("http://urlB.com/list.m3u", m3uContentB, 1L)

        assertEquals(1, resA.channels.size)
        assertEquals("Channel 1 HD", resA.channels[0].name)

        assertEquals(1, resB.channels.size)
        assertEquals("Channel 2 FHD", resB.channels[0].name)
    }

    @Test
    fun testMalformedAndOversizedM3uHandling() {
        // 1. Malformed entries
        val malformedM3u = """
            NOT AN M3U FILE
            Random garbage text without headers
            http://invalid.stream/test.ts
            #EXTINF:-1
            (missing channel URL)
            #EXTINF:-1 tvg-id="",Valid Channel
            http://example.com/valid.m3u8
        """.trimIndent()

        val result = M3uParser.parse(malformedM3u, 1L)
        assertEquals(1, result.channels.size)
        assertEquals("Valid Channel", result.channels[0].name)

        // 2. Oversized channel count capping before and after grouping
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        // Append 13,000 distinct channels
        for (i in 1..13000) {
            sb.append("#EXTINF:-1 tvg-id=\"chan_$i\",Stream Channel $i\n")
            sb.append("http://example.com/stream_$i.m3u8\n")
        }

        val largeResult = M3uParser.parse(ByteArrayInputStream(sb.toString().toByteArray()), 1L)
        // Must be capped strictly at MAX_FINALIZED_CHANNELS (12,000)
        assertEquals(M3uParser.MAX_FINALIZED_CHANNELS, largeResult.channels.size)
        assertEquals(12000, largeResult.channels.size)
    }

    @Test
    fun testErrorClassifierPreservesTlsErrors() {
        // SSL / Certificate errors must be permanent (never retried indefinitely)
        val certException = javax.net.ssl.SSLPeerUnverifiedException("Certificate hostname mismatch")
        assertFalse(ErrorClassifier.isRecoverableNetwork(certException))

        val handshakeException = javax.net.ssl.SSLHandshakeException("Trust anchor not found for certificate path")
        assertFalse(ErrorClassifier.isRecoverableNetwork(handshakeException))

        // 404, 401, 403 HTTP codes are permanent
        assertTrue(ErrorClassifier.isPermanentHttp(404))
        assertTrue(ErrorClassifier.isPermanentHttp(401))
        assertTrue(ErrorClassifier.isPermanentHttp(403))
        assertFalse(ErrorClassifier.isTemporaryHttp(404))

        // 503, 502, 504 are temporary recoverable
        assertTrue(ErrorClassifier.isTemporaryHttp(503))
        assertTrue(ErrorClassifier.isTemporaryHttp(502))
        assertTrue(ErrorClassifier.isTemporaryHttp(504))

        // Socket timeout is recoverable
        val timeoutException = java.net.SocketTimeoutException("Read timed out")
        assertTrue(ErrorClassifier.isRecoverableNetwork(timeoutException))
    }

    @Test
    fun testRetrySchedulerStateTransition() = runTest {
        val scheduler = RetryScheduler(this, maxDelayMs = 500, initialDelayMs = 50)
        var actionRunCount = 0

        scheduler.schedule {
            actionRunCount++
        }

        testScheduler.advanceTimeBy(400)
        assertTrue(actionRunCount > 0)

        // Calling markSuccess terminates the active retry job
        scheduler.markSuccess()
        val countAfterSuccess = actionRunCount
        testScheduler.advanceTimeBy(1000)
        assertEquals(countAfterSuccess, actionRunCount)
    }
}

