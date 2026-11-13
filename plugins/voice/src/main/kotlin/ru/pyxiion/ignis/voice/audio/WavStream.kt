package ru.pyxiion.ignis.voice.audio

import java.io.IOException
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/** WAV through `javax.sound`, converted to 16-bit little-endian PCM whatever the file's encoding. */
class WavStream private constructor(
    private val pcm: AudioInputStream,
    override val rate: Int,
    override val channels: Int,
) : AudioStream {
    private var bytes = ByteArray(0)

    override fun read(buf: FloatArray): Int {
        val wanted = buf.size / channels * 2 * channels
        if (bytes.size < wanted) bytes = ByteArray(wanted)
        var got = 0
        while (got < wanted) {
            val n = pcm.read(bytes, got, wanted - got)
            if (n < 0) break
            got += n
        }
        val samples = got / 2
        if (samples < channels) return -1
        val whole = samples / channels * channels // a torn trailing sample frame is dropped
        for (i in 0 until whole) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            buf[i] = ((hi shl 8) or lo) / 32768f
        }
        return whole / channels
    }

    override fun close() = pcm.close()

    companion object {
        fun open(path: Path): WavStream {
            val source = try {
                AudioSystem.getAudioInputStream(path.toFile())
            } catch (e: UnsupportedAudioFileException) {
                throw AudioDecodeException("not a supported WAV file: ${path.fileName}", e)
            } catch (e: IOException) {
                throw AudioDecodeException("cannot read ${path.fileName}: ${e.message}", e)
            }
            val f = source.format
            val rate = f.sampleRate.toInt()
            if (rate <= 0 || f.channels <= 0) {
                source.close()
                throw AudioDecodeException("invalid WAV format in ${path.fileName}")
            }
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.sampleRate, 16, f.channels, f.channels * 2, f.sampleRate, false)
            val pcm = try {
                if (f.matches(target)) source else AudioSystem.getAudioInputStream(target, source)
            } catch (e: IllegalArgumentException) {
                source.close()
                throw AudioDecodeException("unsupported WAV encoding (${f.encoding}, ${f.sampleSizeInBits} bit) in ${path.fileName}", e)
            }
            return WavStream(pcm, rate, f.channels)
        }

        /** Length in seconds from the header, or -1 if the file does not state it. */
        fun duration(path: Path): Double = try {
            AudioSystem.getAudioInputStream(path.toFile()).use { s ->
                if (s.frameLength < 0) -1.0 else s.frameLength / s.format.frameRate.toDouble()
            }
        } catch (e: UnsupportedAudioFileException) {
            throw AudioDecodeException("not a supported WAV file: ${path.fileName}", e)
        } catch (e: IOException) {
            throw AudioDecodeException("cannot read ${path.fileName}: ${e.message}", e)
        }
    }
}
