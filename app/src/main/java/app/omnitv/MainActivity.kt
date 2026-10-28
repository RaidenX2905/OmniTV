/*
 * OmniTV Player - High-Performance Live IPTV Engine
 */
package app.omnitv

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import app.omnitv.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.omnitv.data.model.Channel
import app.omnitv.data.parser.M3uParser
import app.omnitv.ui.theme.OmniGlass
import app.omnitv.ui.theme.OmniTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onStart() { super.onStart(); window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onStop() { super.onStop(); window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("omnitv_prefs", Context.MODE_PRIVATE)

        // Enforce landscape playback mode (flips dynamically to both landscape sides via sensor regardless of auto-rotate)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Immersive sticky fullscreen for phones, tablets & TVs
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            OmniTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OmniGlass.Background
                ) {
                    OmniTvPlayerApp(prefs)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun OmniTvPlayerApp(prefs: SharedPreferences) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    // Screen dimension checks for responsive scaling
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val isCompact = screenWidth < 600.dp
    val isTablet = screenWidth in 600.dp..900.dp
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val isKeyboardOpen = imeBottomPx > 0

    var playlistUrl by remember {
        mutableStateOf(prefs.getString("playlist_url", "") ?: "")
    }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playlistLoadError by remember { mutableStateOf<String?>(null) }
    var activeChannelIndex by remember {
        mutableIntStateOf(prefs.getInt("last_channel_index", 0))
    }
    var currentStreamUrl by remember { mutableStateOf("") }
    var currentFallbackIndex by remember { mutableIntStateOf(0) }
    var streamQualityPill by remember { mutableStateOf<String?>(null) }
    var isLoadingPlaylist by remember { mutableStateOf(false) }
    var isSyncingPlaylist by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showChannelGuide by remember { mutableStateOf(false) }
    var channelSearchQuery by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isPlayerBuffering by remember { mutableStateOf(false) }
    var isInitialPlaybackStarted by remember { mutableStateOf(false) }
    val startupTs = remember { SystemClock.elapsedRealtime() }
    val channelListState = rememberLazyListState(initialFirstVisibleItemIndex = activeChannelIndex.coerceAtLeast(0))
    val channelFocusRequester = remember { FocusRequester() }
    val guideButtonFocusRequester = remember { FocusRequester() }
    val searchFieldFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Dynamic Orientation: Portrait allowed when entering playlist URL, forced sensorLandscape during TV/playback
    DisposableEffect(showUrlDialog) {
        val activity = context as? ComponentActivity
        if (showUrlDialog) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {}
    }

    // Aspect ratio: 0 = FIT, 1 = FILL (ZOOM/CROP), 2 = STRETCH
    var aspectMode by remember {
        mutableIntStateOf(prefs.getInt("aspect_mode", 0))
    }

    // Bandwidth meter to dynamically adapt bitrate to network conditions
    val bandwidthMeter = remember {
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
            .setResetOnNetworkTypeChange(true)
            .build()
    }

    val diagnostics = remember { PlaybackDiagnostics() }

    // Adaptive LoadControl: fast startup (800ms) with resilient live buffer cushion (2.5s-15s)
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2500,   // minBufferMs: lower floor for instant start without starving playback
                15000,  // maxBufferMs: 15s ceiling prevents excessive memory and latency buildup
                800,    // bufferForPlaybackMs: 800ms start buffer gives fast channel switching
                2000    // bufferForPlaybackAfterRebufferMs: 2s rebuffer cushion to break rebuffer loops
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(3000, true) // Retain 3s back buffer for smooth transient recovery
            .build()
    }

    // Adaptive bitrate selection with conservative start and fast downscale
    val trackSelector = remember {
        val adaptiveTrackSelectionFactory = androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
            1000, // minDurationForQualityIncreaseMs: wait 1s of stable throughput before scaling up
            2500, // maxDurationForQualityDecreaseMs: scale down rapidly within 2.5s if throughput drops
            2000, // minDurationToRetainAfterDiscardMs
            0.75f // bandwidthFraction: use 75% of measured bandwidth to leave headroom for IPTV bursts
        )
        DefaultTrackSelector(context, adaptiveTrackSelectionFactory).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioMimeType(null)
                    .setForceHighestSupportedBitrate(false)
                    .setForceLowestBitrate(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setMaxVideoBitrate(8_000_000) // 8Mbps max preserves crisp 1080p without 4K memory thrash
                    .setMaxVideoSize(1920, 1080)
            )
        }
    }

    val okHttpClient = remember {
        OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(16, 120, TimeUnit.SECONDS))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val exoPlayer = remember {
        val audioNormalizer = app.omnitv.audio.AdaptiveAudioNormalizer()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(audioNormalizer))
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true) // Fall back to secondary codecs if primary decoder stalls
        }

        val loadErrorHandlingPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                // For HTTP 4xx errors, never retry
                val ex = loadErrorInfo.exception
                if (ex is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    if (ErrorClassifier.isPermanentHttp(ex.responseCode)) {
                        return androidx.media3.common.C.TIME_UNSET // Do not retry permanent HTTP errors
                    }
                }
                return super.getRetryDelayMsFor(loadErrorInfo).coerceAtMost(3000L)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("Mozilla/5.0 (OmniTV Universal)")
                .setTransferListener(bandwidthMeter)
        )
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .setLiveTargetOffsetMs(3500) // 3.5s live offset: perfect balance of low latency and zero stalls
            .setLiveMinOffsetMs(1500)
            .setLiveMaxOffsetMs(10000)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                addAnalyticsListener(diagnostics)
            }
    }

    val retryScheduler = remember { RetryScheduler(coroutineScope, maxDelayMs = 12000, initialDelayMs = 1000) }
    val connectivityMonitor = remember { ConnectivityMonitor(context) }
    val isNetworkConnected by connectivityMonitor.isConnected.collectAsState()
    val activeNetworkType by connectivityMonitor.networkType.collectAsState()



    fun playlistKeyFor(url: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(url.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun playlistCacheFileFor(url: String): File =
        File(context.cacheDir, "playlist_${playlistKeyFor(url)}.m3u")

    fun playlistEtagFileFor(url: String): File =
        File(context.cacheDir, "playlist_${playlistKeyFor(url)}.etag")

    fun playlistLastModFileFor(url: String): File =
        File(context.cacheDir, "playlist_${playlistKeyFor(url)}.lastmod")

    fun loadM3u(urlStr: String, isInitialCached: Boolean = false) {
        if (urlStr.isBlank()) {
            showUrlDialog = true
            return
        }
        if (!isInitialCached) {
            isLoadingPlaylist = true
            playlistLoadError = null
        }
        coroutineScope.launch {
            try {
                val parsedChannels = withContext(Dispatchers.IO) {
                    val key = playlistKeyFor(urlStr)
                    val cacheFile = playlistCacheFileFor(urlStr)
                    val etagFile = playlistEtagFileFor(urlStr)
                    val lastModFile = playlistLastModFileFor(urlStr)

                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (OmniTV Universal)")
                    // ETag / conditional refresh isolated per playlist URL
                    if (etagFile.exists()) conn.setRequestProperty("If-None-Match", etagFile.readText().trim())
                    if (lastModFile.exists()) conn.setRequestProperty("If-Modified-Since", lastModFile.readText().trim())
                    
                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode == 304 && cacheFile.exists() && cacheFile.length() > 0) {
                        // HTTP 304 Not Modified: reuse URL-isolated cached channels safely
                        M3uParser.parse(cacheFile.inputStream(), 1L).channels
                    } else if (responseCode in 200..299) {
                        // Check content-length header if provided (max 40 MB)
                        val contentLength = conn.contentLengthLong
                        if (contentLength > 40 * 1024 * 1024) {
                            throw IllegalStateException("Playlist exceeds 40MB limit")
                        }
                        conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
                        conn.getHeaderField("Last-Modified")?.let { lastModFile.writeText(it) }

                        // Bound reading up to 40 MB to protect memory on low-end hardware
                        val maxBytes = 40 * 1024 * 1024
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var totalRead = 0
                        conn.inputStream.use { stream ->
                            while (true) {
                                val r = stream.read(chunk)
                                if (r == -1) break
                                totalRead += r
                                if (totalRead > maxBytes) {
                                    throw IllegalStateException("Playlist exceeds 40MB limit")
                                }
                                buffer.write(chunk, 0, r)
                            }
                        }
                        val bytes = buffer.toByteArray()
                        val text = bytes.toString(Charsets.UTF_8)
                        try { cacheFile.writeBytes(bytes) } catch (_: Exception) {}
                        val res = M3uParser.parseCached(key, text, 1L)
                        res.channels
                    } else {
                        throw java.io.IOException("HTTP $responseCode from server")
                    }
                }
                if (parsedChannels.isNotEmpty()) {
                    channels = parsedChannels
                    playlistLoadError = null
                    prefs.edit().putString("playlist_url", urlStr).apply()
                    val savedChannelUrl = prefs.getString("last_channel_url", null)
                    val targetIdx = if (savedChannelUrl != null) {
                        val found = parsedChannels.indexOfFirst { it.streamUrl == savedChannelUrl }
                        if (found >= 0) found else prefs.getInt("last_channel_index", 0).coerceIn(parsedChannels.indices)
                    } else {
                        prefs.getInt("last_channel_index", 0).coerceIn(parsedChannels.indices)
                    }
                    if (activeChannelIndex != targetIdx) {
                        activeChannelIndex = targetIdx
                    }
                    playerError = null
                } else {
                    if (channels.isEmpty()) {
                        playlistLoadError = "Playlist contains 0 playable channels."
                    }
                }
            } catch (e: Exception) {
                if (channels.isEmpty()) {
                    playlistLoadError = e.localizedMessage ?: "Failed to load playlist"
                }
            } finally {
                isLoadingPlaylist = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (playlistUrl.isNotBlank()) {
            val cacheFile = playlistCacheFileFor(playlistUrl)
            // Instant startup from isolated disk cache
            withContext(Dispatchers.IO) {
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    try {
                        val cachedRes = M3uParser.parse(cacheFile.inputStream(), 1L)
                        if (cachedRes.channels.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                channels = cachedRes.channels
                                val savedChannelUrl = prefs.getString("last_channel_url", null)
                                val targetIdx = if (savedChannelUrl != null) {
                                    val found = cachedRes.channels.indexOfFirst { it.streamUrl == savedChannelUrl }
                                    if (found >= 0) found else prefs.getInt("last_channel_index", 0).coerceIn(cachedRes.channels.indices)
                                } else {
                                    prefs.getInt("last_channel_index", 0).coerceIn(cachedRes.channels.indices)
                                }
                                activeChannelIndex = targetIdx
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            // Background sync/refresh
            loadM3u(playlistUrl, isInitialCached = channels.isNotEmpty())
        } else {
            showUrlDialog = true
        }
    }

    // Auto-dismiss stream quality/status pill
    LaunchedEffect(streamQualityPill) {
        if (streamQualityPill != null) {
            delay(2800)
            streamQualityPill = null
        }
    }

    fun tuneChannel(channel: Channel, fallbackIndex: Int = 0) {
        retryScheduler.cancel()
        playerError = null
        val allStreams = listOf(channel.streamUrl) + channel.alternativeStreamUrls
        val safeFallbackIndex = fallbackIndex.coerceIn(0, allStreams.lastIndex)
        val selectedUrl = allStreams[safeFallbackIndex]

        currentStreamUrl = selectedUrl
        currentFallbackIndex = safeFallbackIndex

        prefs.edit()
            .putInt("last_channel_index", activeChannelIndex)
            .putString("last_channel_url", channel.streamUrl)
            .apply()

        diagnostics.startSession()
        val mediaItem = MediaItem.Builder()
            .setUri(selectedUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3500) // 3.5s live offset: low latency and zero stalls on IPTV
                    .setMinOffsetMs(1500)
                    .setMaxOffsetMs(10000)
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(Unit) {
        connectivityMonitor.start(
            onNetworkRestored = {
                // Auto-recover current channel playback when internet connectivity returns
                coroutineScope.launch(Dispatchers.Main) {
                    if (channels.isNotEmpty() && activeChannelIndex in channels.indices) {
                        val ch = channels[activeChannelIndex]
                        streamQualityPill = "Network restored: resuming playback…"
                        retryScheduler.cancel()
                        tuneChannel(ch, currentFallbackIndex)
                    }
                }
            }
        )
        onDispose {
            connectivityMonitor.stop()
            retryScheduler.cancel()
        }
    }

    fun failoverToNextStream(reason: String) {
        if (channels.isEmpty() || activeChannelIndex !in channels.indices) return
        val channel = channels[activeChannelIndex]
        val allStreams = listOf(channel.streamUrl) + channel.alternativeStreamUrls

        if (allStreams.size > 1 && currentFallbackIndex + 1 < allStreams.size) {
            val nextIndex = currentFallbackIndex + 1
            streamQualityPill = "Buffering detected: auto-switching to stream #${nextIndex + 1}"
            tuneChannel(channel, nextIndex)
        } else if (allStreams.size > 1) {
            // Cycled through all variants, restart primary stream with clean connection
            streamQualityPill = "Re-connecting to primary stream…"
            tuneChannel(channel, 0)
        } else {
            // Single stream, perform fast reconnect without stalling user
            val mediaItem = MediaItem.Builder()
                .setUri(channel.streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(3500)
                        .setMinOffsetMs(1500)
                        .setMaxOffsetMs(10000)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    // Auto-failover watchdog: if playback stays buffering for > 7.5 seconds on mobile data, automatically switch stream
    LaunchedEffect(isPlayerBuffering, currentStreamUrl) {
        if (isPlayerBuffering && isInitialPlaybackStarted) {
            delay(7500)
            if (isPlayerBuffering) {
                failoverToNextStream("Buffering timeout")
            }
        }
    }

    var zapBannerChannel by remember { mutableStateOf<Channel?>(null) }
    var zapBannerIndex by remember { mutableIntStateOf(0) }
    val zapDebounceFlow = remember { MutableSharedFlow<Pair<Int, Channel>>(extraBufferCapacity = 1) }

    // Channel Zap Banner auto-dismiss after 2.5 seconds
    LaunchedEffect(zapBannerChannel) {
        if (zapBannerChannel != null) {
            delay(2500)
            zapBannerChannel = null
        }
    }

    // Debounced channel switching so fast zap presses don't freeze ExoPlayer
    LaunchedEffect(Unit) {
        zapDebounceFlow.collectLatest { (idx, ch) ->
            delay(180) // 180ms debounce for rapid D-pad taps
            withContext(Dispatchers.Main) {
                tuneChannel(ch, 0)
            }
        }
    }

    fun zapToChannel(newIndex: Int) {
        if (channels.isEmpty()) return
        val safeIndex = (newIndex % channels.size + channels.size) % channels.size
        activeChannelIndex = safeIndex
        val ch = channels[safeIndex]
        zapBannerIndex = safeIndex + 1
        zapBannerChannel = ch
        zapDebounceFlow.tryEmit(safeIndex to ch)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val isOriginal = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 ||
                                (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                                format.roleFlags != 0
                            if (isOriginal && group.isTrackSupported(i)) {
                                trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                 )
                                break
                            }
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                isInitialPlaybackStarted = true
                retryScheduler.markSuccess()
                val firstFrameMs = SystemClock.elapsedRealtime() - startupTs
                android.util.Log.d("OmniTV", "firstFrameMs=" + firstFrameMs)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isPlayerBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    isInitialPlaybackStarted = true
                    retryScheduler.markSuccess()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isPlayerBuffering = false
                isInitialPlaybackStarted = true

                if (channels.isNotEmpty() && activeChannelIndex in channels.indices) {
                    val channel = channels[activeChannelIndex]
                    val allStreams = listOf(channel.streamUrl) + channel.alternativeStreamUrls
                    // 1. Try alternate quality/source stream if available
                    if (allStreams.size > 1 && currentFallbackIndex + 1 < allStreams.size) {
                        val nextIndex = currentFallbackIndex + 1
                        streamQualityPill = "Stream offline: auto-switching to stream #${nextIndex + 1}"
                        tuneChannel(channel, nextIndex)
                        return
                    }

                    // 2. Check if error is recoverable network issue
                    if (ErrorClassifier.isRecoverableNetwork(error)) {
                        streamQualityPill = "Network glitch: retrying connection…"
                        retryScheduler.schedule {
                            withContext(Dispatchers.Main) {
                                if (channels.isNotEmpty() && activeChannelIndex in channels.indices) {
                                    val ch = channels[activeChannelIndex]
                                    tuneChannel(ch, currentFallbackIndex)
                                }
                            }
                        }
                        return
                    }
                }
                playerError = "Playback error: ${error.errorCodeName}"
            }
        }

        exoPlayer.addListener(listener)
        onDispose {
            retryScheduler.cancel()
            exoPlayer.removeAnalyticsListener(diagnostics)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(activeChannelIndex, channels) {
        if (channels.isNotEmpty() && activeChannelIndex in channels.indices) {
            val targetChannel = channels[activeChannelIndex]
            val curItem = exoPlayer.currentMediaItem
            val targetUrl = targetChannel.streamUrl
            if (curItem == null || curItem.localConfiguration?.uri?.toString() != targetUrl) {
                tuneChannel(targetChannel, 0)
            }
        }
    }

    // Auto-hide HUD overlay after 5 seconds of inactivity
    var userInteractionTs by remember { mutableLongStateOf(0L) }
    fun notifyUserInteraction() {
        userInteractionTs = SystemClock.elapsedRealtime()
    }
    val resetOverlayTimer = remember {
        object {
            fun tryEmit(unit: Unit) { notifyUserInteraction() }
        }
    }

    LaunchedEffect(showOverlay, showChannelGuide, showUrlDialog, showExitDialog, userInteractionTs) {
        if (showOverlay && !showUrlDialog && !showExitDialog) {
            delay(5000)
            showOverlay = false
            showChannelGuide = false
        }
    }

    BackHandler(enabled = true) {
        when {
            showExitDialog -> showExitDialog = false
            showUrlDialog -> showUrlDialog = false
            showChannelGuide && channelSearchQuery.isNotEmpty() -> channelSearchQuery = ""
            showChannelGuide -> showChannelGuide = false
            showOverlay -> showOverlay = false
            else -> showExitDialog = true
        }
        resetOverlayTimer.tryEmit(Unit)
    }

    var aspectPillMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(aspectPillMessage) {
        if (aspectPillMessage != null) {
            delay(2200)
            aspectPillMessage = null
        }
    }

    fun setAspectMode(mode: Int) {
        aspectMode = mode
        prefs.edit().putInt("aspect_mode", aspectMode).apply()
        val name = when (aspectMode) {
            1 -> "Aspect Ratio: Fill (Zoom)"
            2 -> "Aspect Ratio: Stretch"
            else -> "Aspect Ratio: Fit"
        }
        aspectPillMessage = name
        resetOverlayTimer.tryEmit(Unit)
    }

    fun cycleAspectMode() {
        setAspectMode((aspectMode + 1) % 3)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    resetOverlayTimer.tryEmit(Unit)
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (showUrlDialog || showExitDialog) {
                                false
                            } else if (!showOverlay) {
                                // Fullscreen: open HUD overlay and focus Channel Guide button
                                showOverlay = true
                                showChannelGuide = false
                                true
                            } else {
                                // Overlay is visible: let focused button/item handle OK/ENTER exactly once
                                false
                            }
                        }
                        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> {
                            showOverlay = true
                            showChannelGuide = !showChannelGuide
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!showOverlay && channels.isNotEmpty()) {
                                // Fast zap channel backwards in fullscreen only
                                zapToChannel(activeChannelIndex - 1)
                                true
                            } else {
                                // Overlay visible: let Compose focus navigate between buttons
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!showOverlay && channels.isNotEmpty()) {
                                // Fast zap channel forwards in fullscreen only
                                zapToChannel(activeChannelIndex + 1)
                                true
                            } else {
                                // Overlay visible: let Compose focus navigate between buttons
                                false
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            if (channels.isNotEmpty()) {
                                zapToChannel(activeChannelIndex - 1)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                            if (channels.isNotEmpty()) {
                                zapToChannel(activeChannelIndex + 1)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            // Up does NOT zap channels. When guide is hidden but overlay is open, opens guide.
                            if (showOverlay && !showChannelGuide) {
                                showChannelGuide = true
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // Down does NOT zap channels. When guide is open and at top, lets user navigate.
                            if (showOverlay && !showChannelGuide) {
                                showChannelGuide = true
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            if (!showOverlay) {
                                showOverlay = true
                            } else {
                                showUrlDialog = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_PROG_GREEN,
                        KeyEvent.KEYCODE_PROG_YELLOW,
                        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                        KeyEvent.KEYCODE_0,
                        KeyEvent.KEYCODE_NUMPAD_0 -> {
                            // Remote Aspect Ratio shortcut: Green, Yellow, Audio, 0 key
                            cycleAspectMode()
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            when {
                                showExitDialog -> showExitDialog = false
                                showUrlDialog -> showUrlDialog = false
                                showChannelGuide && channelSearchQuery.isNotEmpty() -> channelSearchQuery = ""
                                showChannelGuide -> showChannelGuide = false
                                showOverlay -> showOverlay = false
                                else -> showExitDialog = true
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showOverlay = !showOverlay
                if (!showOverlay) {
                    showChannelGuide = false
                }
                resetOverlayTimer.tryEmit(Unit)
            }
    ) {
        val currentResizeMode = when (aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        // Adaptive HUD sizing tokens
        val horizontalPadding = when {
            isCompact -> 16.dp
            isTablet -> 28.dp
            else -> 42.dp
        }
        val verticalPadding = when {
            isCompact -> 16.dp
            isTablet -> 22.dp
            else -> 30.dp
        }
        val maxChannelListHeight = when {
            isCompact -> (screenHeight * 0.46f)
            isTablet -> (screenHeight * 0.40f)
            else -> 320.dp
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = currentResizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = currentResizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Error card
        if (playerError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6140505))
                    .border(1.dp, Color(0x66FF5252), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = playerError ?: "",
                    color = Color(0xFFFF8B8B),
                    fontSize = if (isCompact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Stream Quality & Failover Notification Pill
        AnimatedVisibility(
            visible = streamQualityPill != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isCompact) 20.dp else 40.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xF0111624))
                    .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = streamQualityPill ?: "",
                    color = Color.White,
                    fontSize = if (isCompact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Fast TV Aspect Ratio Notification Pill
        AnimatedVisibility(
            visible = aspectPillMessage != null && streamQualityPill == null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isCompact) 20.dp else 40.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xF0111624))
                    .border(1.5.dp, OmniGlass.AccentNeon, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = aspectPillMessage ?: "",
                    color = Color.White,
                    fontSize = if (isCompact) 14.sp else 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Ephemeral Quick Zap Channel Banner (Visible only while switching channels via Left/Right remote)
        AnimatedVisibility(
            visible = zapBannerChannel != null && !showOverlay,
            enter = fadeIn(animationSpec = tween(100)) + slideInVertically { it / 2 },
            exit = fadeOut(animationSpec = tween(150)) + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(
                    start = if (isCompact) 16.dp else 36.dp,
                    bottom = if (isCompact) 20.dp else 36.dp
                )
        ) {
            val ch = zapBannerChannel
            if (ch != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xEE111624))
                        .border(1.2.dp, OmniGlass.GlassBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(OmniGlass.AccentPill)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = zapBannerIndex.toString().padStart(2, '0'),
                            color = OmniGlass.AccentNeon,
                            fontSize = if (isCompact) 13.sp else 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column(modifier = Modifier.widthIn(max = if (isCompact) 220.dp else 400.dp)) {
                        Text(
                            text = ch.displayName.ifBlank { ch.name },
                            color = OmniGlass.TextPrimary,
                            fontSize = if (isCompact) 14.sp else 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ch.qualityLabel.isNotBlank()) {
                            Text(
                                text = "${ch.qualityLabel} • Live",
                                color = OmniGlass.AccentNeon,
                                fontSize = if (isCompact) 11.sp else 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Modern Translucent Frosted Glass Overlay (Scales to phone, tablet, and TV)
        AnimatedVisibility(
            visible = showOverlay,
                    enter = fadeIn(animationSpec = tween(120, easing = FastOutSlowInEasing)),
                    exit = fadeOut(animationSpec = tween(90, easing = FastOutSlowInEasing)),
                    modifier = Modifier.fillMaxSize()
                ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OmniGlass.OverlayGradient)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Tapping background of overlay dismisses controls and guide immediately
                        showOverlay = false
                        showChannelGuide = false
                        resetOverlayTimer.tryEmit(Unit)
                    }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = if (isKeyboardOpen) 4.dp else verticalPadding
                    )
            ) {
                // Top Header: Sleek Glass Island (Auto-hidden while typing on soft keyboard to preserve full viewport)
                AnimatedVisibility(
                    visible = !isKeyboardOpen,
                    enter = fadeIn(animationSpec = tween(80)),
                    exit = fadeOut(animationSpec = tween(60)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    val curCh = channels.getOrNull(activeChannelIndex)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 16.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        // OmniTV Brand Tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(OmniGlass.AccentPill)
                                .border(1.dp, Color(0x4D7C93FF), RoundedCornerShape(8.dp))
                                .padding(
                                    horizontal = if (isCompact) 8.dp else 12.dp,
                                    vertical = if (isCompact) 4.dp else 6.dp
                                )
                        ) {
                            Text(
                                text = "OMNI TV",
                                color = OmniGlass.AccentNeon,
                                fontSize = if (isCompact) 11.sp else 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Column {
                            Text(
                                text = curCh?.displayName ?: curCh?.name ?: "OmniTV Player",
                                color = OmniGlass.TextPrimary,
                                fontSize = when {
                                    isCompact -> 18.sp
                                    isTablet -> 21.sp
                                    else -> 24.sp
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (curCh?.qualityLabel?.isNotBlank() == true) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(OmniGlass.AccentNeon)
                                    )
                                    Text(
                                        text = "${curCh.qualityLabel} • Original Audio",
                                        color = OmniGlass.TextSecondary,
                                        fontSize = if (isCompact) 11.sp else 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Action Pills (Fit / Fill / Stretch, Playlist)
                    // Action Pills (Guide, Aspect Ratio Selector, Playlist URL)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val aspectLabel = when (aspectMode) {
                            1 -> if (isCompact) "Fill" else "Aspect: Fill"
                            2 -> if (isCompact) "Stretch" else "Aspect: Stretch"
                            else -> if (isCompact) "Fit" else "Aspect: Fit"
                        }

                        // 1. Channel Guide button
                        LaunchedEffect(showOverlay) {
                            if (showOverlay && !showChannelGuide) {
                                try {
                                    guideButtonFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                            }
                        }

                        OmniGlassPillButton(
                            text = if (isCompact) "Guide" else "Channel Guide",
                            compact = isCompact,
                            selected = showChannelGuide,
                            focusRequester = guideButtonFocusRequester,
                            onClick = {
                                showChannelGuide = !showChannelGuide
                                resetOverlayTimer.tryEmit(Unit)
                            }
                        )

                        // 2. Aspect Ratio Selector (Fit, Fill, Stretch - Instant cycle on click/OK)
                        OmniGlassPillButton(
                            text = aspectLabel,
                            compact = isCompact,
                            onClick = {
                                cycleAspectMode()
                                resetOverlayTimer.tryEmit(Unit)
                            }
                        )

                        // 3. Playlist URL button
                        OmniGlassPillButton(
                            text = if (isCompact) "URL" else "Playlist URL",
                            compact = isCompact,
                            onClick = {
                                showUrlDialog = true
                                resetOverlayTimer.tryEmit(Unit)
                            }
                        )
                    }
                }
            }

            // Channel Guide Shelf (toggled via Channel Guide button or D-pad)
            AnimatedVisibility(
                    visible = showChannelGuide,
                    enter = fadeIn(animationSpec = tween(120, easing = FastOutSlowInEasing)) +
                            slideInVertically(
                                animationSpec = tween(120, easing = FastOutSlowInEasing),
                                initialOffsetY = { it / 8 }
                            ),
                    exit = fadeOut(animationSpec = tween(90, easing = FastOutSlowInEasing)) +
                           slideOutVertically(
                               animationSpec = tween(90, easing = FastOutSlowInEasing),
                               targetOffsetY = { it / 8 }
                           ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    val filteredChannelsWithIndex = remember(channels, channelSearchQuery) {
                        if (channelSearchQuery.isBlank()) {
                            channels.mapIndexed { idx, ch -> idx to ch }
                        } else {
                            val q = channelSearchQuery.trim().lowercase()
                            channels.mapIndexed { idx, ch -> idx to ch }.filter { (_, ch) ->
                                ch.name.lowercase().contains(q) ||
                                ch.displayName.lowercase().contains(q) ||
                                ch.groupKey.lowercase().contains(q) ||
                                (ch.number != null && ch.number.toString().contains(q))
                            }
                        }
                    }

                    LaunchedEffect(showChannelGuide) {
                        if (showChannelGuide) {
                            if (channelSearchQuery.isNotBlank()) {
                                channelSearchQuery = ""
                            }
                            if (activeChannelIndex in channels.indices) {
                                try {
                                    channelListState.scrollToItem((activeChannelIndex - 1).coerceAtLeast(0))
                                    channelFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    // Glass container card for channel list + search
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .clip(RoundedCornerShape(if (isCompact) 14.dp else 20.dp))
                            .background(Color(0xEE111624))
                            .border(
                                1.dp,
                                OmniGlass.GlassBorder,
                                RoundedCornerShape(if (isCompact) 14.dp else 20.dp)
                            )
                            .padding(
                                horizontal = if (isCompact) 8.dp else 14.dp,
                                vertical = if (isKeyboardOpen) 4.dp else if (isCompact) 6.dp else 10.dp
                            )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(if (isKeyboardOpen) 3.dp else if (isCompact) 4.dp else 8.dp)
                        ) {
                            // Search Bar with full TV remote navigation
                            OutlinedTextField(
                                value = channelSearchQuery,
                                onValueChange = {
                                    channelSearchQuery = it
                                    resetOverlayTimer.tryEmit(Unit)
                                },
                                placeholder = {
                                    Text(
                                        text = "Search channels by name, category, or number…",
                                        color = OmniGlass.TextSecondary,
                                        fontSize = if (isCompact) 11.sp else 13.sp
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        keyboardController?.hide()
                                        if (filteredChannelsWithIndex.isNotEmpty()) {
                                            try {
                                                firstResultFocusRequester.requestFocus()
                                            } catch (_: Exception) {}
                                        } else {
                                            focusManager.clearFocus()
                                        }
                                        resetOverlayTimer.tryEmit(Unit)
                                    }
                                ),
                                shape = RoundedCornerShape(if (isCompact) 8.dp else 12.dp),
                                trailingIcon = {
                                    if (channelSearchQuery.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                channelSearchQuery = ""
                                                resetOverlayTimer.tryEmit(Unit)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Text(
                                                text = "Clear",
                                                color = OmniGlass.AccentNeon,
                                                fontSize = if (isCompact) 11.sp else 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = OmniGlass.AccentNeon,
                                    unfocusedBorderColor = Color(0x33FFFFFF),
                                    focusedContainerColor = Color(0x22FFFFFF),
                                    unfocusedContainerColor = Color(0x11FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFieldFocusRequester)
                                    .onFocusChanged {
                                        if (it.isFocused) resetOverlayTimer.tryEmit(Unit)
                                    }
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            when (keyEvent.nativeKeyEvent.keyCode) {
                                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                    if (filteredChannelsWithIndex.isNotEmpty()) {
                                                        keyboardController?.hide()
                                                        try {
                                                            firstResultFocusRequester.requestFocus()
                                                        } catch (_: Exception) {
                                                            focusManager.moveFocus(FocusDirection.Down)
                                                        }
                                                        true
                                                    } else false
                                                }
                                                KeyEvent.KEYCODE_DPAD_UP -> {
                                                    try {
                                                        guideButtonFocusRequester.requestFocus()
                                                        true
                                                    } catch (_: Exception) {
                                                        false
                                                    }
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                            )

                            // Dynamic list height: scales cleanly between phones with open keyboard and TVs
                            val dynamicListHeight = when {
                                isKeyboardOpen -> (screenHeight - imeBottomDp - 120.dp).coerceIn(80.dp, 160.dp)
                                isCompact -> (screenHeight * 0.44f).coerceAtLeast(150.dp)
                                else -> maxChannelListHeight
                            }

                            if (filteredChannelsWithIndex.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = if (isKeyboardOpen) 6.dp else if (isCompact) 10.dp else 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No channels match \"$channelSearchQuery\"",
                                        color = OmniGlass.TextSecondary,
                                        fontSize = if (isCompact) 11.sp else 14.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = channelListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = dynamicListHeight),
                                    verticalArrangement = Arrangement.spacedBy(if (isKeyboardOpen) 3.dp else if (isCompact) 4.dp else 6.dp)
                                ) {
                                    itemsIndexed(
                                        items = filteredChannelsWithIndex,
                                        key = { _, (_, ch) -> ch.streamUrl },
                                        contentType = { _, _ -> "channel_item" }
                                    ) { displayIdx, (origIdx, ch) ->
                                        val isCurrent = origIdx == activeChannelIndex
                                        val itemFocusRequester = when {
                                            displayIdx == 0 -> firstResultFocusRequester
                                            isCurrent && channelSearchQuery.isBlank() -> channelFocusRequester
                                            else -> null
                                        }
                                        OmniGlassChannelRow(
                                            channel = ch,
                                            index = origIdx + 1,
                                            isPlaying = isCurrent,
                                            compact = isCompact || isKeyboardOpen,
                                            focusRequester = itemFocusRequester,
                                            onFocused = {
                                                resetOverlayTimer.tryEmit(Unit)
                                            },
                                            onClick = {
                                                activeChannelIndex = origIdx
                                                tuneChannel(ch)
                                                showChannelGuide = false
                                                showOverlay = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hero Startup & Playlist Sync Screen (Glassmorphism + Neon Infinity Logo)
        AnimatedVisibility(
            visible = (!isInitialPlaybackStarted && playlistUrl.isNotBlank() && playerError == null && playlistLoadError == null) || (channels.isEmpty() && (isLoadingPlaylist || playlistLoadError != null)),
            enter = fadeIn(animationSpec = tween(0)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OmniGlass.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val logoBoxSize = when {
                    isCompact -> 90.dp
                    isTablet -> 120.dp
                    else -> 140.dp
                }
                val logoImgSize = when {
                    isCompact -> 80.dp
                    isTablet -> 110.dp
                    else -> 130.dp
                }

                // 1. Logo placed strictly at geometric center (50% X, 50% Y)
                Box(
                    modifier = Modifier
                        .size(logoBoxSize)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x331E293B))
                        .border(1.5.dp, OmniGlass.GlassBorder, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "OmniTV Hero Logo",
                        modifier = Modifier.size(logoImgSize)
                    )
                }

                // 2. Status texts & progress neatly docked below center with generous clearance
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (isCompact) 28.dp else 44.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "OMNI TV",
                        color = Color.White,
                        fontSize = if (isCompact) 18.sp else 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val statusText = when {
                        playlistLoadError != null -> playlistLoadError ?: "Playlist loading error"
                        isLoadingPlaylist -> "Synchronizing playlist channels…"
                        else -> "Preparing stream engine…"
                    }
                    Text(
                        text = statusText,
                        color = if (playlistLoadError != null) Color(0xFFFF8B8B) else OmniGlass.TextSecondary,
                        fontSize = if (isCompact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (playlistLoadError != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OmniGlassPillButton(
                                text = "Retry",
                                compact = isCompact,
                                onClick = {
                                    playlistLoadError = null
                                    loadM3u(playlistUrl)
                                }
                            )
                            OmniGlassPillButton(
                                text = "Edit URL",
                                compact = isCompact,
                                onClick = {
                                    showUrlDialog = true
                                }
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            color = OmniGlass.AccentNeon,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(if (isCompact) 24.dp else 28.dp)
                        )
                    }
                }
            }
        }

        // Subtly Indicated Live Stream Buffering (Non-blocking, minimalist glass pill)
        AnimatedVisibility(
            visible = isPlayerBuffering && channels.isNotEmpty() && isInitialPlaybackStarted,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xD90D111A))
                    .border(1.dp, OmniGlass.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = OmniGlass.AccentNeon,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Buffering stream…",
                        color = OmniGlass.TextPrimary,
                        fontSize = if (isCompact) 13.sp else 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Playlist URL Dialog
        if (showUrlDialog) {
            var draftUrl by remember(playlistUrl) { mutableStateOf(playlistUrl) }
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = {
                    Text(
                        "Configure Playlist",
                        color = OmniGlass.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 18.sp else 20.sp
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Enter live M3U or M3U8 link for OmniTV:",
                            color = OmniGlass.TextSecondary,
                            fontSize = if (isCompact) 12.sp else 13.sp
                        )
                        OutlinedTextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            placeholder = { Text("https://example.com/playlist.m3u") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = OmniGlass.AccentNeon,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedContainerColor = Color(0x22FFFFFF),
                                unfocusedContainerColor = Color(0x11FFFFFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OmniGlass.AccentNeon),
                        onClick = {
                            val target = draftUrl.trim()
                            playlistUrl = target
                            showUrlDialog = false
                            loadM3u(target)
                        }
                    ) {
                        Text("Connect", color = OmniGlass.TextDark, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) {
                        Text("Cancel", color = OmniGlass.TextSecondary)
                    }
                },
                containerColor = Color(0xF2121724),
                shape = RoundedCornerShape(20.dp)
            )
        }

        // Stay or Leave Exit Dialog
        if (showExitDialog) {
            val stayFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                try {
                    stayFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }

            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = {
                    Text(
                        "Exit OmniTV?",
                        color = OmniGlass.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 18.sp else 20.sp
                    )
                },
                text = {
                    Text(
                        "Choose Stay to continue watching, or Leave to close app and free all memory and resources.",
                        color = OmniGlass.TextSecondary,
                        fontSize = if (isCompact) 13.sp else 14.sp
                    )
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OmniGlassPillButton(
                            text = "Stay",
                            compact = isCompact,
                            focusRequester = stayFocusRequester,
                            onClick = { showExitDialog = false }
                        )
                        OmniGlassPillButton(
                            text = "Leave",
                            compact = isCompact,
                            danger = true,
                            onClick = {
                                showExitDialog = false
                                try {
                                    exoPlayer.stop()
                                    exoPlayer.clearMediaItems()
                                    exoPlayer.release()
                                } catch (_: Exception) {}
                                (context as? ComponentActivity)?.finishAffinity()
                            }
                        )
                    }
                },
                dismissButton = null,
                containerColor = Color(0xF2121724),
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
fun OmniGlassChannelRow(
    channel: Channel,
    index: Int,
    isPlaying: Boolean,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val background = when {
        isFocused -> OmniGlass.GlassCardFocused
        isPlaying -> OmniGlass.GlassCardPlaying
        else -> OmniGlass.GlassCard
    }

    val border = when {
        isFocused -> OmniGlass.GlassBorderFocused
        isPlaying -> OmniGlass.GlassBorderPlaying
        else -> OmniGlass.GlassBorder
    }

    val titleColor = if (isFocused) OmniGlass.TextDark else OmniGlass.TextPrimary
    val subColor = when {
        isFocused -> Color(0xFF4B5565)
        isPlaying -> OmniGlass.AccentNeon
        else -> OmniGlass.TextSecondary
    }

    val rowShape = if (compact) RoundedCornerShape(8.dp) else RoundedCornerShape(12.dp)
    val indexText = remember(index) { if (index < 10) "0$index" else "$index" }
    val displayTitle = remember(channel.displayName, channel.name) {
        channel.displayName.ifBlank { channel.name }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(rowShape)
            .background(background)
            .border(
                width = if (isFocused || isPlaying) 2.dp else 1.dp,
                color = border,
                shape = rowShape
            )
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocused()
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 9.dp else 12.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = indexText,
                color = subColor,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (isFocused || isPlaying) FontWeight.Bold else FontWeight.Medium
            )

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 8.dp else 10.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) Color(0xFF2563EB) else OmniGlass.AccentNeon)
                )
            }

            Text(
                text = displayTitle,
                color = titleColor,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = if (isPlaying || isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (channel.qualityLabel.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isFocused) Color(0x22000000) else OmniGlass.AccentPill
                    )
                    .border(
                        0.5.dp,
                        if (isFocused) Color(0x33000000) else Color(0x337C93FF),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(
                        horizontal = if (compact) 6.dp else 8.dp,
                        vertical = if (compact) 2.dp else 3.dp
                    )
            ) {
                Text(
                    text = channel.qualityLabel,
                    color = if (isFocused) OmniGlass.TextDark else OmniGlass.AccentNeon,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OmniGlassPillButton(
    text: String,
    compact: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = tween(100),
        label = "pillScale"
    )

    val background = when {
        isFocused && danger -> Color(0xFFFF2222)
        isFocused -> OmniGlass.GlassCardFocused
        danger -> Color(0x33FF3333)
        selected -> OmniGlass.AccentNeon
        else -> OmniGlass.GlassCard
    }

    val border = when {
        isFocused && danger -> Color(0xFFFFFFFF)
        isFocused -> OmniGlass.GlassBorderFocused
        danger -> Color(0x88FF3333)
        selected -> OmniGlass.AccentNeon
        else -> OmniGlass.GlassBorder
    }

    val textColor = when {
        isFocused && danger -> Color.White
        isFocused -> OmniGlass.TextDark
        danger -> Color(0xFFFF9999)
        selected -> OmniGlass.TextDark
        else -> OmniGlass.TextPrimary
    }

    val borderWidth = if (isFocused) 3.dp else 1.dp
    val pillShape = RoundedCornerShape(if (compact) 10.dp else 14.dp)

    Box(
        modifier = Modifier
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(pillShape)
            .background(background)
            .border(
                borderWidth,
                border,
                pillShape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 7.dp else 10.dp
            )
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}
// LOW-END INSTRUMENTATION (debug-only, no production noise)
/* Timing markers (not logged unless debug):
- Activity created -> first frame: startupTs (SystemClock.elapsedRealtime())
- Cache read -> usable channels
- Playlist HTTP -> parsed
- Player prepare -> STATE_READY
- Channel switch -> first video frame
Measured by source inspection; runtime measurements unverified (no device).
*/
