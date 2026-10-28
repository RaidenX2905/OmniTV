package app.omnitv

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * Lightweight privacy-safe playback and network diagnostics.
 * Records startup time, buffering events, throughput, HTTP status codes,
 * and track selection without leaking credentials, tokens, or private playlist URLs.
 */
@UnstableApi
class PlaybackDiagnostics : AnalyticsListener {

    companion object {
        private const val TAG = "OmniTV-Diag"
    }

    var startupTimeMs: Long = 0
        private set
    var totalBufferingTimeMs: Long = 0
        private set
    var rebufferCount: Int = 0
        private set
    var totalBytesLoaded: Long = 0
        private set
    var currentBitrateBps: Int = 0
        private set

    private var sessionStartNs: Long = 0
    private var bufferStartNs: Long = 0
    private var isCurrentlyBuffering = false

    private fun currentTimeNanos(): Long = System.nanoTime()

    fun startSession() {
        sessionStartNs = currentTimeNanos()
        bufferStartNs = 0
        isCurrentlyBuffering = false
        startupTimeMs = 0
        totalBufferingTimeMs = 0
        rebufferCount = 0
        totalBytesLoaded = 0
        currentBitrateBps = 0
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = currentTimeNanos()
        when (state) {
            Player.STATE_BUFFERING -> {
                if (!isCurrentlyBuffering) {
                    isCurrentlyBuffering = true
                    bufferStartNs = now
                    rebufferCount++
                    safeLogD("Buffering started (event #$rebufferCount)")
                }
            }
            Player.STATE_READY -> {
                if (isCurrentlyBuffering) {
                    isCurrentlyBuffering = false
                    val durationMs = (now - bufferStartNs) / 1_000_000
                    totalBufferingTimeMs += durationMs
                    safeLogD("Buffering ended in ${durationMs}ms (total: ${totalBufferingTimeMs}ms)")
                }
                if (startupTimeMs == 0L && sessionStartNs > 0) {
                    startupTimeMs = (now - sessionStartNs) / 1_000_000
                    safeLogI("Initial stream ready in ${startupTimeMs}ms")
                }
            }
            Player.STATE_ENDED -> {
                isCurrentlyBuffering = false
            }
            Player.STATE_IDLE -> {
                isCurrentlyBuffering = false
            }
        }
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        totalBytesLoaded += loadEventInfo.bytesLoaded
        val statusCode = (loadEventInfo.responseHeaders["_status"]?.firstOrNull()
            ?: loadEventInfo.responseHeaders[":status"]?.firstOrNull()) ?: "OK"
        safeLogD("HTTP load completed: bytes=${loadEventInfo.bytesLoaded}, durationMs=${loadEventInfo.loadDurationMs}, status=$statusCode")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        val errorType = error.javaClass.simpleName
        safeLogW("HTTP load error: type=$errorType, canceled=$wasCanceled, durationMs=${loadEventInfo.loadDurationMs}")
    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        val format = mediaLoadData.trackFormat
        if (format != null) {
            currentBitrateBps = format.bitrate
            safeLogI("Adaptive stream track changed: ${format.width}x${format.height} @ ${format.bitrate / 1000}kbps")
        }
    }

    private fun safeLogD(msg: String) {
        try { android.util.Log.d(TAG, msg) } catch (_: Throwable) { println("[$TAG-D] $msg") }
    }

    private fun safeLogI(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG-I] $msg") }
    }

    private fun safeLogW(msg: String) {
        try { android.util.Log.w(TAG, msg) } catch (_: Throwable) { println("[$TAG-W] $msg") }
    }
}
