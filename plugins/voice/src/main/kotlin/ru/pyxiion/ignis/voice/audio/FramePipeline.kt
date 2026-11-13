package ru.pyxiion.ignis.voice.audio

import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.roundToInt

const val SAMPLE_RATE = 48_000
const val FRAME_SAMPLES = 960
const val FRAME_SECONDS = FRAME_SAMPLES.toDouble() / SAMPLE_RATE

/** Channel downmix: every channel counts equally. */
fun downmixToMono(src: FloatArray, frames: Int, channels: Int, dst: FloatArray) {
    if (channels == 1) {
        System.arraycopy(src, 0, dst, 0, frames)
        return
    }
    val inv = 1f / channels
    var i = 0
    for (f in 0 until frames) {
        var sum = 0f
        repeat(channels) { sum += src[i++] }
        dst[f] = sum * inv
    }
}

fun floatToPcm16(v: Float): Short = (v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()

/**
 * An [AudioStream] turned into the common voice format, 20 ms frames of 48 kHz mono signed 16-bit PCM.
 * The last frame is padded with silence.
 */
class FramePipeline(private val stream: AudioStream) : Closeable {
    private val channels = stream.channels
    private val resampler = if (stream.rate == SAMPLE_RATE) null else Resampler(stream.rate, SAMPLE_RATE)
    private val readBuf = FloatArray(4096 * channels)
    private val mono = FloatArray(4096)
    private val out = FloatArray(4096)
    private var outLen = 0
    private var outPos = 0
    private var inputFrames = 0L
    private var pulled = 0L
    private var inputDone = false

    /** The next 960 samples, or null once the stream is exhausted. */
    fun nextFrame(): ShortArray? {
        val frame = ShortArray(FRAME_SAMPLES)
        var filled = 0
        while (filled < FRAME_SAMPLES) {
            if (outPos == outLen && !refill()) break
            val n = minOf(FRAME_SAMPLES - filled, outLen - outPos)
            for (i in 0 until n) frame[filled + i] = floatToPcm16(out[outPos + i])
            outPos += n
            filled += n
        }
        return if (filled == 0) null else frame
    }

    /** Fills [out] with more mono 48 kHz samples; false when nothing is left. */
    private fun refill(): Boolean {
        outPos = 0
        outLen = 0
        while (true) {
            if (resampler != null) {
                // Everything the resampler already holds comes out before more input is read.
                // The kernel tail after the last input sample is silence: cut at the length the input implies.
                val limit = if (inputDone) minOf(out.size.toLong(), expectedOut() - pulled).toInt() else out.size
                val n = if (limit > 0) resampler.pull(out, 0, limit) else 0
                if (n > 0) {
                    pulled += n
                    outLen = n
                    return true
                }
            }
            if (inputDone) return false
            val frames = stream.read(readBuf)
            if (frames < 0) {
                inputDone = true
                resampler?.flush()
                continue
            }
            downmixToMono(readBuf, frames, channels, mono)
            inputFrames += frames
            if (resampler == null) {
                System.arraycopy(mono, 0, out, 0, frames)
                outLen = frames
                return true
            }
            resampler.push(mono, 0, frames)
        }
    }

    private fun expectedOut(): Long = ceil(inputFrames.toDouble() * SAMPLE_RATE / stream.rate).toLong()

    override fun close() = stream.close()
}
