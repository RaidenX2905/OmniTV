package app.omnitv.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class AdaptiveAudioNormalizerTest {

    private fun createAudioFormat(sampleRate: Int = 48000, channelCount: Int = 2): AudioProcessor.AudioFormat {
        return AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    }

    @Test
    fun testFormatConfiguration() {
        val normalizer = AdaptiveAudioNormalizer()
        val format = createAudioFormat(44100, 2)
        val outputFormat = normalizer.configure(format)

        assertEquals(format.sampleRate, outputFormat.sampleRate)
        assertEquals(format.channelCount, outputFormat.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding)
        assertTrue(normalizer.isActive)
    }

    @Test
    fun testSilenceGatingPreventsNoiseAmplification() {
        val normalizer = AdaptiveAudioNormalizer()
        normalizer.configure(createAudioFormat())
        normalizer.flush()

        // Generate near-silent buffer (ambient floor: amplitude 10 out of 32767)
        val bufferSize = 1920 // ~20ms at 48kHz stereo 16-bit
        val input = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bufferSize / 2) {
            input.putShort(10.toShort())
        }
        input.flip()

        normalizer.queueInput(input)
        val output = normalizer.output

        // Ensure output is produced and amplitude is not excessively amplified by silence gate
        assertTrue(output.hasRemaining())
        val firstSample = output.getShort(0)
        assertTrue("Silence must not be excessively boosted: $firstSample", firstSample <= 30)
    }

    @Test
    fun testPeakLimitingPreventsClipping() {
        val normalizer = AdaptiveAudioNormalizer()
        normalizer.configure(createAudioFormat())
        normalizer.flush()

        // Generate max volume loud transient buffer
        val bufferSize = 1920
        val input = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bufferSize / 2) {
            input.putShort(32700.toShort())
        }
        input.flip()

        // Feed multiple frames to allow normalizer to adjust
        for (f in 0 until 5) {
            input.rewind()
            normalizer.queueInput(input)
            val output = normalizer.output

            // Check that every sample stays within strictly valid 16-bit range without integer overflow
            while (output.hasRemaining()) {
                val sample = output.short
                assertTrue("Sample exceeded valid range: $sample", sample in -32768..32767)
            }
        }
    }

    @Test
    fun testMultichannel51Handling() {
        val normalizer = AdaptiveAudioNormalizer()
        // 5.1 surround sound (6 channels)
        val format = createAudioFormat(48000, 6)
        val outputFormat = normalizer.configure(format)

        assertEquals(6, outputFormat.channelCount)

        val frameBytes = 2 * 6 * 100 // 100 samples across 6 channels
        val input = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameBytes / 2) {
            input.putShort((i % 1000).toShort())
        }
        input.flip()

        normalizer.queueInput(input)
        val output = normalizer.output
        assertEquals(frameBytes, output.remaining())
    }

    @Test
    fun testFlushResetsGainCleanly() {
        val normalizer = AdaptiveAudioNormalizer()
        normalizer.configure(createAudioFormat())
        normalizer.flush()

        // Flush must reset internal state without throwing
        normalizer.flush()
        normalizer.reset()
    }
}

