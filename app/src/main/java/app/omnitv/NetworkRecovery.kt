package app.omnitv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

object ErrorClassifier {
    fun isRecoverableNetwork(t: Throwable): Boolean {
        val lower = (t.message ?: "").lowercase()
        val causeLower = (t.cause?.message ?: "").lowercase()
        val combined = "$lower $causeLower"
        // Permanent / non-recoverable errors
        if (combined.contains("certificate") ||
            combined.contains("sslhandshake") ||
            combined.contains("sslpeerunverified") ||
            combined.contains("unsupported") ||
            combined.contains("404") ||
            combined.contains("401") ||
            combined.contains("403") ||
            combined.contains("malformed")
        ) {
            return false
        }
        return true
    }

    fun isPermanentHttp(code: Int): Boolean = code in 400..499 && code != 408 && code != 429
    fun isTemporaryHttp(code: Int): Boolean = code in 500..599 || code == 408 || code == 429
}

class RetryScheduler(
    private val scope: CoroutineScope,
    private val maxDelayMs: Long = 12000,
    private val initialDelayMs: Long = 800
) {
    private var attempt = 0
    private var job: Job? = null
    @Volatile
    private var isCancelled = false

    fun schedule(action: suspend () -> Boolean) {
        cancel()
        isCancelled = false
        job = scope.launch {
            while (!isCancelled) {
                // Exponential backoff with jitter: initialDelay * 2^min(attempt, 4) + jitter
                val exponential = initialDelayMs * (1L shl min(attempt, 4))
                val delayWithCap = min(exponential, maxDelayMs)
                val jitter = Random.nextLong(0, 300)
                delay(delayWithCap + jitter)
                if (isCancelled) break
                val success = try {
                    action()
                } catch (_: Exception) {
                    false
                }
                if (success) {
                    attempt = 0
                    return@launch
                }
                attempt++
            }
        }
    }

    fun cancel() {
        isCancelled = true
        job?.cancel()
        job = null
        attempt = 0
    }
}

class ConnectivityMonitor(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isConnected = MutableStateFlow(checkInitialConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _networkType = MutableStateFlow(getNetworkTypeName())
    val networkType: StateFlow<String> = _networkType.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun checkInitialConnectivity(): Boolean {
        val cm = connectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun getNetworkTypeName(): String {
        val cm = connectivityManager ?: return "UNKNOWN"
        val active = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(active) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    fun start(onNetworkRestored: (() -> Unit)? = null) {
        val cm = connectivityManager ?: return
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasDisconnected = !_isConnected.value
                _isConnected.value = true
                _networkType.value = getNetworkTypeName()
                if (wasDisconnected) {
                    onNetworkRestored?.invoke()
                }
            }

            override fun onLost(network: Network) {
                _isConnected.value = checkInitialConnectivity()
                _networkType.value = getNetworkTypeName()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _isConnected.value = hasInternet && isValidated
                _networkType.value = getNetworkTypeName()
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(builder.build(), callback)
        } catch (_: Exception) {
            // Fallback: leave default connected state
        }
    }

    fun stop() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
