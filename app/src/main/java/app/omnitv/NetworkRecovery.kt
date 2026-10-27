package app.omnitv

import kotlinx.coroutines.*
object ErrorClassifier {
    fun isRecoverableNetwork(t: Throwable): Boolean {
        val lower = (t.message ?: "").lowercase()
        return !(lower.contains("tls") || lower.contains("ssl") || lower.contains("certificate") || lower.contains("manifest") || lower.contains("unsupported") || lower.contains("404"))
    }
    fun isPermanentHttp(c: Int) = c in 401..499
    fun isTemporaryHttp(c: Int) = c in 500..599
}

class RetryScheduler(private val s: kotlinx.coroutines.CoroutineScope, private val mx: Long = 15000, private val initD: Long = 1000) {
    private var a = 0; private var j: kotlinx.coroutines.Job? = null; private var cancelled = false
    fun schedule(action: suspend () -> Boolean) { cancel(); cancelled = false; j = s.launch { while (!cancelled) { val d = kotlin.math.min(initD * (1L shl kotlin.math.min(a,5)), mx) + (0L..250L).random(); kotlinx.coroutines.delay(d); if (cancelled) break; val ok = try { action() } catch(_: Exception) { false }; if (ok) { a = 0; return@launch }; a++ } } }
    fun cancel() { cancelled = true; j?.cancel(); j = null; a = 0 }
}
