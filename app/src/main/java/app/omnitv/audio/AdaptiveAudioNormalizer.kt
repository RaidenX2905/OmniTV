package app.omnitv.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Adaptive Audio Normalizer and Dynamic Range Compressor for OmniTV.
 *
 * Provides always-on perceived loudness normalization across IPTV streams:
 * - Gradually amplifies quiet content/dialogue toward a consistent reference loudness.
 * - Compresses loud scenes and advertisements to comfortable listening levels.
 * - Peak limiter prevents digital clipping and distortion.
 * - Rolling RMS energy estimation with asymmetric attack/release smoothing prevents audible pumping.
 * - Gate threshold ignores background noise and complete silence so silence is not boosted.
 * - Operates entirely on raw 16-bit PCM before device volume is applied.
 */
@UnstableApi
class AdaptiveAudioNormalizer : BaseAudioProcessor() {

    companion object {
        // Target RMS level (~ -16 dBFS) for clear, broadcast-grade dialogue
        const val TARGET_RMS = 4500.0f

        // Silence / noise floor gate (~ -45 dBFS); do not amplify silence
        const val NOISE_GATE_RMS = 180.0f

        // Max gain boost factor (~ +9.5 dB)
        const val MAX_GAIN = 3.0f

        // Min gain attenuation factor (~ -12 dB)
        const val MIN_GAIN = 0.25f

        // Dynamic range compression ratio above knee
        const val COMPRESSION_RATIO = 2.5f

        // Smoothing coefficients (per frame block)
        const val ATTACK_COEFF = 0.08f   // fast reaction to loud surges
        const val RELEASE_COEFF = 0.003f // gentle return for quiet dialogue
    }

    private var smoothedRms: Float = TARGET_RMS
    private var currentGain: Float = 1.0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        smoothedRms = TARGET_RMS
        currentGain = 1.0f
    }

    override fun onReset() {
        smoothedRms = TARGET_RMS
        currentGain = 1.0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val bytesToProcess = limit - position
        if (bytesToProcess <= 0) return

        val outputBuffer = replaceOutputBuffer(bytesToProcess)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val channelCount = inputAudioFormat.channelCount
        val sampleCount = bytesToProcess / (2 * channelCount)
        if (sampleCount <= 0) {
            inputBuffer.position(limit)
            return
        }

        // 1. Calculate RMS energy of current audio window
        var sumSquares = 0.0
        var totalSamples = 0
        var idx = position
        while (idx < limit) {
            val sample = inputBuffer.getShort(idx)
            sumSquares += (sample.toDouble() * sample.toDouble())
            totalSamples++
            idx += 2
        }

        val frameRms = if (totalSamples > 0) sqrt(sumSquares / totalSamples).toFloat() else 0.0f

        // 2. Compute adaptive target gain with silence gating and compression
        val targetGain = when {
            // Silence / background noise floor: maintain neutral gain, do not amplify
            frameRms < NOISE_GATE_RMS -> 1.0f
            else -> {
                // Update running smoothed loudness level
                val coeff = if (frameRms > smoothedRms) ATTACK_COEFF else RELEASE_COEFF
                smoothedRms += (frameRms - smoothedRms) * coeff

                val desiredGain = TARGET_RMS / max(smoothedRms, 1.0f)

                // Compress high dynamics above unity gain
                val compressedGain = if (desiredGain > 1.0f) {
                    1.0f + (desiredGain - 1.0f) / COMPRESSION_RATIO
                } else {
                    desiredGain
                }

                compressedGain.coerceIn(MIN_GAIN, MAX_GAIN)
            }
        }

        // 3. Smooth gain transitions to eliminate audible clicks or sudden jumps
        val gainCoeff = if (targetGain < currentGain) ATTACK_COEFF else RELEASE_COEFF
        currentGain += (targetGain - currentGain) * gainCoeff

        // 4. Apply gain and transparent soft peak limiter to prevent clipping
        idx = position
        while (idx < limit) {
            val sample = inputBuffer.getShort(idx)
            val amplified = sample * currentGain

            // Soft-knee peak limiting to strictly prevent 16-bit PCM overflow
            val limited = when {
                amplified > 32767.0f -> {
                    val excess = amplified - 32767.0f
                    32767.0f - (excess / 4.0f).coerceAtMost(500.0f)
                }
                amplified < -32768.0f -> {
                    val excess = -32768.0f - amplified
                    -32768.0f + (excess / 4.0f).coerceAtMost(500.0f)
                }
                else -> amplified
            }

            outputBuffer.putShort(limited.toInt().coerceIn(-32768, 32767).toShort())
            idx += 2
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }
}

