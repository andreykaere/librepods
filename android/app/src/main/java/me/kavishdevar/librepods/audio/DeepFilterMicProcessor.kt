package me.kavishdevar.librepods.audio

import android.content.Context
import android.util.Log
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stateful DeepFilterNet stream processor for the AirPods 64 kHz mono microphone. */
class DeepFilterMicProcessor(
    context: Context,
    private val onPcm: (ByteArray) -> Unit,
    private val onStatus: (Status) -> Unit = {}
) : AutoCloseable {
    enum class Status { DISABLED, LOADING, READY, FAILED }

    private val appContext = context.applicationContext
    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val input = ArrayBlockingQueue<ByteArray>(128)
    private val worker = Thread(::processLoop, "librepods-deep-filter").apply {
        priority = Thread.NORM_PRIORITY
        start()
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        onStatus(if (value) Status.LOADING else Status.DISABLED)
    }

    fun offer(pcm: ByteArray) {
        if (closed.get()) return
        if (!input.offer(pcm.copyOf())) {
            Log.w(TAG, "DeepFilterNet is behind; dropping oldest pending audio frame")
            input.poll()
            input.offer(pcm.copyOf())
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.clear()
            worker.interrupt()
        }
    }

    private fun processLoop() {
        var model: NativeDeepFilterNet? = null
        var stream: StreamingFilter? = null
        var lastEnabled = false
        try {
            while (!closed.get()) {
                val shouldFilter = enabled.get()
                if (shouldFilter && model == null) {
                    try {
                        model = NativeDeepFilterNet(appContext)
                        model?.setAttenuationLimit(30f)
                        stream = StreamingFilter(model!!)
                        onStatus(Status.READY)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Could not initialize DeepFilterNet", t)
                        enabled.set(false)
                        onStatus(Status.FAILED)
                    }
                } else if (shouldFilter && !lastEnabled && model != null) {
                    onStatus(Status.READY)
                } else if (!shouldFilter && lastEnabled) {
                    stream?.reset()
                    onStatus(Status.DISABLED)
                }
                lastEnabled = enabled.get()

                val pcm = input.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val output = if (enabled.get() && stream != null) {
                    try {
                        stream!!.process(pcm)
                    } catch (t: Throwable) {
                        Log.e(TAG, "DeepFilterNet processing failed", t)
                        enabled.set(false)
                        onStatus(Status.FAILED)
                        pcm
                    }
                } else {
                    pcm
                }
                if (output.isNotEmpty()) onPcm(output)
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

    private class StreamingFilter(private val model: NativeDeepFilterNet) {
        private val frameBytes = model.frameLength.toInt().also {
            require(it > 0 && it % 2 == 0) { "DeepFilterNet reported invalid frame size: $it" }
        }
        private val frameSamples = frameBytes / 2
        private val frameBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        private val input64k = FloatQueue()
        private val input48kFrames = FloatQueue()
        private val output48k = FloatQueue()
        private var inputPosition = 0.0
        private var outputPosition = 0.0

        fun process(pcm: ByteArray): ByteArray {
            var byteIndex = 0
            while (byteIndex + 1 < pcm.size) {
                val sample = ((pcm[byteIndex].toInt() and 0xff) or (pcm[byteIndex + 1].toInt() shl 8)).toShort()
                input64k.add(sample / 32768f)
                byteIndex += 2
            }

            while (inputPosition + 1.0 < input64k.size) {
                input48kFrames.add(input64k.interpolate(inputPosition))
                inputPosition += INPUT_64K_PER_OUTPUT_48K
            }
            input64k.discard(inputPosition.toInt())
            inputPosition -= inputPosition.toInt()

            while (input48kFrames.size >= frameSamples) {
                frameBuffer.clear()
                repeat(frameSamples) {
                    frameBuffer.putShort((input48kFrames.removeFirst() * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
                frameBuffer.flip()
                model.processFrame(frameBuffer)
                frameBuffer.position(0)
                while (frameBuffer.remaining() >= 2) output48k.add(frameBuffer.short / 32768f)
            }

            val result = ByteArrayOutputBuilder()
            while (outputPosition + 1.0 < output48k.size) {
                val sample = output48k.interpolate(outputPosition)
                val pcm16 = (sample * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                result.add(pcm16)
                outputPosition += OUTPUT_48K_PER_64K_OUTPUT
            }
            output48k.discard(outputPosition.toInt())
            outputPosition -= outputPosition.toInt()
            return result.toByteArray()
        }

        fun reset() {
            input64k.clear()
            input48kFrames.clear()
            output48k.clear()
            inputPosition = 0.0
            outputPosition = 0.0
        }
    }

    private class FloatQueue(initialCapacity: Int = 4096) {
        private var values = FloatArray(initialCapacity)
        private var start = 0
        private var end = 0
        val size: Int get() = end - start

        fun add(value: Float) {
            ensureCapacity(1)
            values[end++] = value
        }

        fun interpolate(position: Double): Float {
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val first = values[start + index]
            val second = values[start + index + 1]
            return first + (second - first) * fraction
        }

        fun removeFirst(): Float = values[start++]

        fun discard(count: Int) {
            start = (start + count).coerceAtMost(end)
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
            if (end + additional > values.size) values = values.copyOf(maxOf(values.size * 2, end + additional))
        }
    }

    private class ByteArrayOutputBuilder {
        private var values = ByteArray(2048)
        private var size = 0
        fun add(value: Short) {
            if (size + 2 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = (value.toInt() and 0xff).toByte()
            values[size++] = (value.toInt() shr 8).toByte()
        }
        fun toByteArray(): ByteArray = values.copyOf(size)
    }

    private companion object {
        const val TAG = "DeepFilterMicProcessor"
        const val INPUT_64K_PER_OUTPUT_48K = 4.0 / 3.0
        const val OUTPUT_48K_PER_64K_OUTPUT = 3.0 / 4.0
    }
}
