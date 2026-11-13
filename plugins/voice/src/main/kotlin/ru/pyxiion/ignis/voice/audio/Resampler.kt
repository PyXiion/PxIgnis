package ru.pyxiion.ignis.voice.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming mono resampler: a Hann-windowed sinc kernel, low-passed at the lower of the two Nyquist
 * frequencies so that downsampling does not alias. Feed input with [push], collect output with [pull];
 * [flush] appends the silence needed to drain the kernel tail.
 */
class Resampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val cutoff = min(1.0, outRate.toDouble() / inRate)
    private val half = ceil(ZERO_CROSSINGS / cutoff).toInt()

    private var buf = FloatArray(8192)
    private var len = half // the first `half` samples are the silence before the stream
    private var base = -half.toLong() // absolute input index of buf[0]
    private var pos = 0.0 // absolute input position of the next output sample

    fun push(src: FloatArray, off: Int, n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(src, off, buf, len, n)
        len += n
    }

    fun flush() {
        if (len + half + 1 > buf.size) buf = buf.copyOf(len + half + 1)
        buf.fill(0f, len, len + half + 1)
        len += half + 1
    }

    /** Writes up to [max] output samples that the buffered input allows into [out] and returns their count. */
    fun pull(out: FloatArray, off: Int, max: Int): Int {
        var n = 0
        while (n < max) {
            val center = floor(pos).toLong()
            if (center + half - base >= len) break
            val frac = pos - center
            var acc = 0.0
            for (k in -half + 1..half) acc += buf[(center + k - base).toInt()] * kernel(k - frac)
            out[off + n++] = acc.toFloat()
            pos += step
        }
        val drop = (floor(pos).toLong() - half + 1 - base).toInt().coerceIn(0, len)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            base += drop
        }
        return n
    }

    private fun kernel(x: Double): Double {
        if (abs(x) >= half) return 0.0
        val a = PI * x * cutoff
        val sinc = if (abs(a) < 1e-9) 1.0 else sin(a) / a
        return cutoff * sinc * (0.5 + 0.5 * cos(PI * x / half))
    }

    private companion object {
        const val ZERO_CROSSINGS = 8.0
    }
}
