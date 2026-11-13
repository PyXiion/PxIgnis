package ru.pyxiion.ignis.voice

import ru.pyxiion.ignis.voice.audio.AudioStream
import ru.pyxiion.ignis.voice.audio.Decoder
import ru.pyxiion.ignis.voice.audio.FRAME_SAMPLES
import ru.pyxiion.ignis.voice.audio.FramePipeline
import ru.pyxiion.ignis.voice.audio.Resampler
import ru.pyxiion.ignis.voice.audio.downmixToMono
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioTest {
    private fun sine(freq: Double, rate: Int, n: Int) = FloatArray(n) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val r = Resampler(inRate, outRate)
        val out = ArrayList<Float>()
        val buf = FloatArray(1024)
        var pos = 0
        while (pos < input.size) {
            val n = minOf(777, input.size - pos) // odd chunk size on purpose
            r.push(input, pos, n)
            pos += n
            while (true) {
                val got = r.pull(buf, 0, buf.size)
                if (got == 0) break
                for (i in 0 until got) out.add(buf[i])
            }
        }
        r.flush()
        while (true) {
            val got = r.pull(buf, 0, buf.size)
            if (got == 0) break
            for (i in 0 until got) out.add(buf[i])
        }
        return out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun resamplerKeepsToneAndLength() {
        val out = resample(sine(1000.0, 44100, 44100), 44100, 48000)
        assertTrue(abs(out.size - 48000) < 100, "length ${out.size}")
        // A 0.5-amplitude sine has RMS 0.3536; the passband must not change it.
        assertEquals(0.3536, rms(out, 2000, 46000), 0.01)
        // The phase is right too: compare with the ideal 48 kHz sine.
        val ideal = sine(1000.0, 48000, 48000)
        var err = 0.0
        for (i in 2000 until 46000) err = maxOf(err, abs((out[i] - ideal[i]).toDouble()))
        assertTrue(err < 0.02, "max error $err")
    }

    @Test
    fun resamplerRemovesAliasingWhenDownsampling() {
        // 30 kHz is above the 24 kHz Nyquist of the output: it must be filtered out, not folded to 18 kHz.
        val out = resample(sine(30000.0, 96000, 96000), 96000, 48000)
        assertTrue(abs(out.size - 48000) < 100)
        assertTrue(rms(out, 2000, 46000) < 0.05)
        val pass = resample(sine(1000.0, 96000, 96000), 96000, 48000)
        assertEquals(0.3536, rms(pass, 2000, 46000), 0.01)
    }

    @Test
    fun resamplerUpsamplesLowRates() {
        val out = resample(sine(500.0, 8000, 8000), 8000, 48000)
        assertTrue(abs(out.size - 48000) < 100)
        assertEquals(0.3536, rms(out, 2000, 46000), 0.01)
    }

    @Test
    fun downmixAveragesChannels() {
        val dst = FloatArray(3)
        downmixToMono(floatArrayOf(1f, -1f, 0.5f, 0.5f, 1f, 0f), 3, 2, dst)
        assertEquals(listOf(0f, 0.5f, 0.5f), dst.toList())
        downmixToMono(floatArrayOf(0.3f, 0.6f, 0.9f), 1, 3, dst)
        assertEquals(0.6f, dst[0], 1e-6f)
        downmixToMono(floatArrayOf(0.1f, 0.2f), 2, 1, dst)
        assertEquals(listOf(0.1f, 0.2f), dst.take(2))
    }

    private class Fake(override val rate: Int, override val channels: Int, private val data: FloatArray) : AudioStream {
        private var pos = 0
        override fun read(buf: FloatArray): Int {
            if (pos >= data.size) return -1
            val n = minOf(buf.size, data.size - pos) / channels * channels
            System.arraycopy(data, pos, buf, 0, n)
            pos += n
            return n / channels
        }

        override fun close() {}
    }

    @Test
    fun pipelineFramesAre960SamplesAndLastIsPadded() {
        val pipe = FramePipeline(Fake(48000, 1, FloatArray(2500) { 0.5f }))
        val frames = generateSequence { pipe.nextFrame() }.toList()
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == FRAME_SAMPLES })
        assertEquals(16384, frames[0][0].toInt())
        assertEquals(16384, frames[2][2500 - 2 * FRAME_SAMPLES - 1].toInt())
        assertEquals(0, frames[2][2500 - 2 * FRAME_SAMPLES].toInt())
    }

    @Test
    fun pipelineDownmixesAndResamplesToExactDuration() {
        // 1 s of stereo at 24 kHz, left and right in antiphase: silence once downmixed, 1 s = 50 frames.
        val n = 24000
        val data = FloatArray(n * 2) { if (it % 2 == 0) 0.5f else -0.5f }
        val pipe = FramePipeline(Fake(24000, 2, data))
        val frames = generateSequence { pipe.nextFrame() }.toList()
        assertEquals(50, frames.size)
        assertTrue(frames.all { f -> f.all { abs(it.toInt()) <= 1 } })
    }

    private fun writeWav(dir: Path, name: String, rate: Float, bits: Int, channels: Int, seconds: Double): Path {
        val frames = (rate * seconds).toInt()
        val bytesPer = bits / 8
        val raw = ByteArray(frames * channels * bytesPer)
        for (f in 0 until frames) {
            val v = 0.5 * sin(2 * PI * 440 * f / rate)
            for (c in 0 until channels) {
                val o = (f * channels + c) * bytesPer
                if (bits == 8) {
                    raw[o] = (v * 127).toInt().plus(128).toByte() // unsigned
                } else {
                    val s = (v * 32767).toInt()
                    raw[o] = s.toByte()
                    raw[o + 1] = (s shr 8).toByte()
                }
            }
        }
        val fmt = AudioFormat(
            if (bits == 8) AudioFormat.Encoding.PCM_UNSIGNED else AudioFormat.Encoding.PCM_SIGNED,
            rate, bits, channels, channels * bytesPer, rate, false,
        )
        val file = dir.resolve(name)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(raw), fmt, frames.toLong()), AudioFileFormat.Type.WAVE, file.toFile())
        return file
    }

    private fun energy(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size) / 32768

    @Test
    fun decodesWavs() {
        val dir = Files.createTempDirectory("voice-test")
        try {
            for ((name, rate, bits, ch) in listOf(
                listOf("a.wav", 48000, 16, 1), listOf("b.wav", 44100, 16, 2), listOf("c.wav", 22050, 8, 1),
            )) {
                val f = writeWav(dir, name as String, (rate as Int).toFloat(), bits as Int, ch as Int, 0.5)
                val probe = Decoder.probe(f)
                assertEquals(0.5, probe.durationSeconds, 0.001, name)
                val pcm = Decoder.decodeAll(f)
                assertEquals(0, pcm.size % FRAME_SAMPLES)
                assertTrue(abs(pcm.size - 24000) <= FRAME_SAMPLES, "$name size ${pcm.size}")
                assertEquals(0.3536, energy(pcm.copyOf(24000)), 0.03, name)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun decodesOggFixture() {
        val url = assertNotNull(javaClass.getResource("/tone-stereo-44k.ogg"))
        val path = Path.of(url.toURI())
        val probe = Decoder.probe(path)
        assertEquals(0.5, probe.durationSeconds, 0.02)
        val pcm = Decoder.decodeAll(path)
        assertEquals(0, pcm.size % FRAME_SAMPLES)
        assertTrue(abs(pcm.size - 24000) <= 2 * FRAME_SAMPLES, "size ${pcm.size}")
        // Left and right are in antiphase: the downmix is (near) silence. This also proves both channels decode.
        assertTrue(energy(pcm) < 0.02, "energy ${energy(pcm)}")
    }

    @Test
    fun rejectsUnknownAndBrokenFiles() {
        val dir = Files.createTempDirectory("voice-test")
        try {
            val mp3 = dir.resolve("a.mp3").also { Files.write(it, ByteArray(10)) }
            assertFailsWith<ru.pyxiion.ignis.voice.audio.AudioDecodeException> { Decoder.probe(mp3) }
            val bad = dir.resolve("bad.ogg").also { Files.write(it, ByteArray(100) { 7 }) }
            assertFailsWith<ru.pyxiion.ignis.voice.audio.AudioDecodeException> { Decoder.decodeAll(bad) }
            val badWav = dir.resolve("bad.wav").also { Files.write(it, ByteArray(100) { 7 }) }
            assertFailsWith<ru.pyxiion.ignis.voice.audio.AudioDecodeException> { Decoder.decodeAll(badWav) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun longFilesStream() {
        val dir = Files.createTempDirectory("voice-test")
        try {
            val f = writeWav(dir, "long.wav", 8000f, 8, 1, 31.0)
            val data = SoundData.decode(f)
            assertEquals(0, data.bytes)
            val c = data.cursor()
            var n = 0
            while (c.next() != null) n++
            assertTrue(abs(n - 31 * 50) <= 1, "frames $n")
            val short = SoundData.decode(writeWav(dir, "short.wav", 8000f, 8, 1, 1.0))
            assertTrue(short.bytes > 0)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cacheEvictsOldestOverLimit() {
        val cache = SoundCache(maxBytes = 4000)
        fun key(n: Int) = SoundKey(Path.of("/x$n"), 1, 1)
        cache.put(key(1), SoundData.ofPcm(ShortArray(960)))
        cache.put(key(2), SoundData.ofPcm(ShortArray(960)))
        cache[key(1)] // 1 is now more recent than 2
        cache.put(key(3), SoundData.ofPcm(ShortArray(960)))
        assertNotNull(cache[key(1)])
        assertEquals(null, cache[key(2)])
        assertNotNull(cache[key(3)])
    }
}
