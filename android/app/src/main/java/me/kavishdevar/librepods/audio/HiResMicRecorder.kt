package me.kavishdevar.librepods.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes AirPods' AACP 0x58 AAC-ELD uplink and stores it as a mono PCM WAV. */
class HiResMicRecorder(
    private val outputFile: File?,
    private val onStopped: (Result<File?>) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onPcm: (ByteArray) -> Unit = {}
) {
    private val running = AtomicBoolean(true)
    private val sdus = ArrayBlockingQueue<ByteArray>(128)
    private val worker = Thread(::decodeLoop, "librepods-hires-mic").apply { start() }

    fun offerSdu(sdu: ByteArray) {
        if (running.get() && !sdus.offer(sdu.copyOf())) {
            Log.w(TAG, "Dropping hi-res microphone SDU because decoder is behind")
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            // The queue is bounded. Make room for the stop marker if capture outpaced decode.
            while (!sdus.offer(STOP, 100, TimeUnit.MILLISECONDS)) sdus.poll()
        }
    }

    private fun decodeLoop() {
        var decoder: MediaCodec? = null
        var wav: WavWriter? = null
        var failure: Throwable? = null
        var decodedBytes = 0L
        try {
            outputFile?.parentFile?.mkdirs()
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(AUDIO_SPECIFIC_CONFIG))
            if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var endOfInput = false
            var inputPtsUs = 0L
            while (!endOfInput || sdus.isNotEmpty()) {
                val sdu = sdus.poll(10, TimeUnit.MILLISECONDS)
                if (sdu === STOP) endOfInput = true
                if (sdu != null && sdu !== STOP) {
                    for (au in demuxType58(sdu)) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex)!!
                            input.clear()
                            if (au.size > input.remaining()) error("AAC-ELD frame exceeds decoder input buffer")
                            input.put(au)
                            decoder.queueInputBuffer(inputIndex, 0, au.size, inputPtsUs, 0)
                            inputPtsUs += FRAME_DURATION_US
                        }
                        val drained = drainOutput(decoder, info, wav, outputFile, false)
                        wav = drained.first
                        decodedBytes += drained.second
                    }
                }
                if (endOfInput) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, inputPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    val drained = drainOutput(decoder, info, wav, outputFile, true)
                    wav = drained.first
                    decodedBytes += drained.second
                } else {
                    val drained = drainOutput(decoder, info, wav, outputFile, false)
                    wav = drained.first
                    decodedBytes += drained.second
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.e(TAG, "Hi-res microphone recording failed", t)
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            decoder?.release()
            try { wav?.close() } catch (t: Throwable) { if (failure == null) failure = t }
            if (failure == null && decodedBytes == 0L) {
                outputFile?.delete()
                failure = IllegalStateException("AirPods did not provide decodable audio")
            }
            onStopped(if (failure == null) Result.success(outputFile) else Result.failure(failure!!))
        }
    }

    private fun drainOutput(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        currentWriter: WavWriter?,
        file: File?,
        drainAll: Boolean
    ): Pair<WavWriter?, Long> {
        var writer = currentWriter
        var bytesWritten = 0L
        var first = true
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, if (first || drainAll) TIMEOUT_US else 0)
            first = false
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return writer to bytesWritten
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = decoder.outputFormat
                    val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) { "Unsupported decoded PCM format: $encoding" }
                    check(channels == CHANNELS) { "Expected mono decoded audio, got $channels channels" }
                    // The AirPods AAC-ELD stream is clocked at 64 kHz. Android codec
                    // implementations may report their internal/output rate differently;
                    // the rewrite recorder also keeps the WAV timeline at 64 kHz.
                    if (file != null) writer = writer ?: WavWriter(file, SAMPLE_RATE, channels)
                }
                index >= 0 -> {
                    val output = decoder.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        onLevel(calculateLevel(output))
                        val pcm = ByteArray(output.remaining())
                        output.duplicate().get(pcm)
                        onPcm(pcm)
                        writer?.write(output)
                        bytesWritten += info.size
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    if (eos) return writer to bytesWritten
                }
            }
        }
    }

    private fun demuxType58(sdu: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var offset = HEADER_LENGTH
        while (offset + 5 <= sdu.size) {
            val size = sdu[offset + 4].toInt() and 0xff
            val start = offset + 5
            if (start + size > sdu.size) break
            if (size > 0) result += sdu.copyOfRange(start, start + size)
            offset = start + size
        }
        return result
    }

    private fun calculateLevel(buffer: ByteBuffer): Float {
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        while (samples.remaining() >= 2) {
            peak = maxOf(peak, kotlin.math.abs(samples.short.toInt()))
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        var dataBytes: Long = 0
            private set

        init {
            output.setLength(0)
            output.write(ByteArray(44))
        }

        fun write(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
            dataBytes += bytes.size
        }

        override fun close() {
            output.seek(0)
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeIntLE((36 + dataBytes).toInt())
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeIntLE(16)
            output.writeShortLE(1)
            output.writeShortLE(channels)
            output.writeIntLE(sampleRate)
            output.writeIntLE(sampleRate * channels * 2)
            output.writeShortLE(channels * 2)
            output.writeShortLE(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeIntLE(dataBytes.toInt())
            output.close()
        }
    }

    companion object {
        private const val TAG = "HiResMicRecorder"
        private const val SAMPLE_RATE = 64000
        private const val CHANNELS = 1
        private const val HEADER_LENGTH = 22
        private const val FRAME_DURATION_US = 7500L
        private const val TIMEOUT_US = 10000L
        private val STOP = ByteArray(0)
        // AAC AudioSpecificConfig used by the Linux decoder (AAC-ELD, mono, 64 kHz).
        private val AUDIO_SPECIFIC_CONFIG = byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30, 0x00)

        private fun RandomAccessFile.writeIntLE(value: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        private fun RandomAccessFile.writeShortLE(value: Int) = write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}
