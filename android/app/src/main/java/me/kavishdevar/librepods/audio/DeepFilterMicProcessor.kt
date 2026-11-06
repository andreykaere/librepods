package me.kavishdevar.librepods.audio

import android.content.Context
import android.util.Log
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Stateful microphone pipeline:
 * 64 kHz mono PCM16 (AirPods AAC-ELD decoder) -> FIR resampler -> 48 kHz mono PCM16
 * -> optional DeepFilterNet -> 48 kHz mono PCM16.
 *
 * The output sample rate is always 48 kHz, including when DeepFilterNet is disabled.
 */
class DeepFilterMicProcessor(
    context: Context,
    private val onPcm: (ByteArray) -> Unit,
    private val onStatus: (Status) -> Unit = {}
) : AutoCloseable {
    enum class Status { DISABLED, LOADING, READY, FAILED }

    private val appContext = context.applicationContext
    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val input = ArrayBlockingQueue<ByteArray>(INPUT_QUEUE_CAPACITY)
    private val droppedInputChunks = AtomicLong(0)
    private val worker = Thread(::processLoop, "librepods-deep-filter").apply { start() }

    fun setEnabled(value: Boolean) {
        if (closed.get()) return
        enabled.set(value)
        onStatus(if (value) Status.LOADING else Status.DISABLED)
    }

    /** Accepts little-endian, signed PCM16, mono, 64 kHz. */
    fun offer(pcm: ByteArray) {
        if (closed.get() || pcm.isEmpty()) return
        val copy = pcm.copyOf()
        if (!input.offer(copy)) {
            input.poll()
            droppedInputChunks.incrementAndGet()
            if (!input.offer(copy)) droppedInputChunks.incrementAndGet()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.clear()
            worker.interrupt()
        }
    }

    private fun processLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not set audio priority for DeepFilterNet worker", t)
        }

        var model: NativeDeepFilterNet? = null
        var stream: StreamingFilter? = null
        var lastEnabled = false
        val resampler = FirResampler64To48()
        var measuredAudioNanos = 0L
        var measuredProcessingNanos = 0L
        var lastStatsNanos = System.nanoTime()

        try {
            while (!closed.get()) {
                val shouldFilter = enabled.get()
                if (shouldFilter && stream == null) {
                    try {
                        val nativeModel = model ?: NativeDeepFilterNet(appContext).also { model = it }
                        awaitModelLoaded(nativeModel)

                        // Preserve the existing user-selected setting exactly as requested.
                        check(nativeModel.setAttenuationLimit(30f)) {
                            "Could not set DeepFilterNet attenuation limit"
                        }
                        stream = StreamingFilter(nativeModel)
                        if (enabled.get() && !closed.get()) onStatus(Status.READY)
                    } catch (t: InterruptedException) {
                        throw t
                    } catch (t: Throwable) {
                        Log.e(TAG, "Could not initialize DeepFilterNet", t)
                        if (enabled.compareAndSet(true, false)) onStatus(Status.FAILED)
                    }
                } else if (shouldFilter && !lastEnabled && stream != null) {
                    onStatus(Status.READY)
                } else if (!shouldFilter && lastEnabled) {
                    stream?.reset()
                    onStatus(Status.DISABLED)
                }
                lastEnabled = enabled.get()

                val pcm64k = input.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val processingStartedNanos = System.nanoTime()
                val pcm48k = resampler.process(pcm64k)
                if (pcm48k.isEmpty()) continue

                val activeStream = if (enabled.get()) stream else null
                val output48k = if (activeStream != null) {
                    try {
                        activeStream.process48k(pcm48k)
                    } catch (t: Throwable) {
                        Log.e(TAG, "DeepFilterNet processing failed", t)
                        enabled.set(false)
                        activeStream.reset()
                        lastEnabled = false
                        onStatus(Status.FAILED)
                        pcm48k
                    }
                } else {
                    pcm48k
                }

                if (activeStream != null) {
                    measuredProcessingNanos += System.nanoTime() - processingStartedNanos
                    measuredAudioNanos += pcm64k.size.toLong() * NANOS_PER_SECOND / INPUT_BYTES_PER_SECOND
                }

                val nowNanos = System.nanoTime()
                if (nowNanos - lastStatsNanos >= STATS_INTERVAL_NANOS) {
                    if (measuredAudioNanos > 0L) {
                        val realtimeFactor = measuredProcessingNanos.toDouble() / measuredAudioNanos
                        Log.i(
                            TAG,
                            String.format(
                                Locale.US,
                                "Live performance: RTF=%.2f, queue=%d/%d, droppedChunks=%d, outputRate=%dHz",
                                realtimeFactor,
                                input.size,
                                INPUT_QUEUE_CAPACITY,
                                droppedInputChunks.getAndSet(0L),
                                OUTPUT_SAMPLE_RATE
                            )
                        )
                    }
                    measuredAudioNanos = 0L
                    measuredProcessingNanos = 0L
                    lastStatsNanos = nowNanos
                }

                if (output48k.isNotEmpty()) onPcm(output48k)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } finally {
            try {
                model?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not release DeepFilterNet", t)
            }
        }
    }

    private fun awaitModelLoaded(model: NativeDeepFilterNet) {
        val loaded = CountDownLatch(1)
        model.onModelLoaded { loaded.countDown() }
        check(loaded.await(MODEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for DeepFilterNet model initialization after $MODEL_LOAD_TIMEOUT_SECONDS seconds"
        }
        check(!closed.get()) { "DeepFilterNet processor was closed during model initialization" }
        check(model.frameLength > 0L) {
            "DeepFilterNet model loaded but reported invalid frame length: ${model.frameLength}"
        }
    }

    /** Stateful frame adapter. Its input and output are both 48 kHz PCM16. */
    private class StreamingFilter(private val model: NativeDeepFilterNet) {
        private val frameBytes = model.frameLength.toInt().also {
            require(it > 0 && it % 2 == 0) { "DeepFilterNet reported invalid frame size: $it" }
        }
        private val frameSamples = frameBytes / 2
        private val frameBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        private val input = FloatQueue(maxOf(frameSamples * 4, 4096))

        fun process48k(pcm: ByteArray): ByteArray {
            var byteIndex = 0
            while (byteIndex + 1 < pcm.size) {
                val sample = ((pcm[byteIndex].toInt() and 0xff) or (pcm[byteIndex + 1].toInt() shl 8)).toShort()
                input.add(sample / 32768f)
                byteIndex += 2
            }

            val output = ByteArrayOutputBuilder()
            while (input.size >= frameSamples) {
                frameBuffer.clear()
                repeat(frameSamples) {
                    val value = (input.removeFirst() * 32768f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    frameBuffer.putShort(value.toShort())
                }
                frameBuffer.flip()
                model.processFrame(frameBuffer)
                frameBuffer.position(0)
                while (frameBuffer.remaining() >= 2) output.add(frameBuffer.short / 32768f)
            }
            return output.toByteArray()
        }

        fun reset() = input.clear()
    }

    /**
     * 64 -> 48 kHz rational resampler (3 output samples per 4 input samples).
     * A 64-tap windowed-sinc low-pass is used before decimation to suppress aliasing.
     * The three fractional phases are precomputed, so the audio loop does not calculate
     * trigonometric coefficients per sample.
     */
    private class FirResampler64To48 {
        private val input = FloatQueue(8192)
        private val coefficients = Array(PHASES) { phase -> createCoefficients(phase.toDouble() / PHASES) }
        private var sourcePosition = LEFT_TAPS.toDouble()

        init {
            repeat(LEFT_TAPS) { input.add(0f) }
        }

        fun process(pcm64k: ByteArray): ByteArray {
            var byteIndex = 0
            while (byteIndex + 1 < pcm64k.size) {
                val sample = ((pcm64k[byteIndex].toInt() and 0xff) or
                    (pcm64k[byteIndex + 1].toInt() shl 8)).toShort()
                input.add(sample / 32768f)
                byteIndex += 2
            }

            val output = ByteArrayOutputBuilder()
            while (sourcePosition + RIGHT_TAPS < input.size) {
                val center = floor(sourcePosition).toInt()
                val fraction = sourcePosition - center
                val phase = (fraction * PHASES).roundToInt().coerceIn(0, PHASES - 1)
                val taps = coefficients[phase]
                var sample = 0.0
                for (tap in 0 until TAPS) {
                    sample += input[center + tap - LEFT_TAPS].toDouble() * taps[tap]
                }
                output.add(sample.toFloat())

                sourcePosition += INPUT_PER_OUTPUT
                val discard = (sourcePosition.toInt() - LEFT_TAPS).coerceAtLeast(0)
                if (discard > 0) {
                    input.discard(discard)
                    sourcePosition -= discard
                }
            }
            return output.toByteArray()
        }

        private fun createCoefficients(fraction: Double): DoubleArray {
            val result = DoubleArray(TAPS)
            var sum = 0.0
            for (tap in 0 until TAPS) {
                val distance = tap - LEFT_TAPS - fraction
                val normalized = distance / WINDOW_RADIUS
                val window = if (abs(normalized) <= 1.0) {
                    0.42 + 0.5 * cos(PI * normalized) + 0.08 * cos(2.0 * PI * normalized)
                } else {
                    0.0
                }
                val x = 2.0 * CUTOFF * distance
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val coefficient = 2.0 * CUTOFF * sinc * window
                result[tap] = coefficient
                sum += coefficient
            }
            if (abs(sum) > 1e-12) {
                for (i in result.indices) result[i] /= sum
            }
            return result
        }

        companion object {
            private const val TAPS = 64
            private const val LEFT_TAPS = 31
            private const val RIGHT_TAPS = 32
            private const val PHASES = 3
            private const val WINDOW_RADIUS = 32.0
            private const val CUTOFF = 0.375
            private const val INPUT_PER_OUTPUT = 4.0 / 3.0
        }
    }

    private class FloatQueue(initialCapacity: Int = 4096) {
        private var values = FloatArray(initialCapacity)
        private var start = 0
        private var end = 0
        val size: Int get() = end - start

        operator fun get(index: Int): Float {
            require(index in 0 until size) { "FloatQueue index $index outside size $size" }
            return values[start + index]
        }

        fun add(value: Float) {
            ensureCapacity(1)
            values[end++] = value
        }

        fun removeFirst(): Float = values[start++]

        fun discard(count: Int) {
            start = (start + count.coerceIn(0, size)).coerceAtMost(end)
            if (start == end) {
                start = 0
                end = 0
            } else if (start > values.size / 2) {
                val remaining = size
                System.arraycopy(values, start, values, 0, remaining)
                start = 0
                end = remaining
            }
        }

        fun clear() {
            start = 0
            end = 0
        }

        private fun ensureCapacity(additional: Int) {
            if (end + additional <= values.size) return
            if (start > 0) {
                val remaining = size
                System.arraycopy(values, start, values, 0, remaining)
                start = 0
                end = remaining
            }
            if (end + additional > values.size) {
                values = values.copyOf(maxOf(values.size * 2, end + additional))
            }
        }
    }

    private class ByteArrayOutputBuilder {
        private var values = ByteArray(4096)
        private var size = 0

        fun add(value: Float) {
            val sample = (value * 32768f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (size + 2 > values.size) values = values.copyOf(maxOf(values.size * 2, size + 2))
            values[size++] = (sample and 0xff).toByte()
            values[size++] = (sample shr 8).toByte()
        }

        fun toByteArray(): ByteArray = values.copyOf(size)
    }

    private companion object {
        const val TAG = "DeepFilterMicProcessor"
        const val INPUT_QUEUE_CAPACITY = 128
        const val MODEL_LOAD_TIMEOUT_SECONDS = 60L
        const val INPUT_SAMPLE_RATE = 64_000
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val INPUT_BYTES_PER_SECOND = INPUT_SAMPLE_RATE * 2L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STATS_INTERVAL_NANOS = 5_000_000_000L
    }
}
