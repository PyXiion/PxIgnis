package ru.pyxiion.ignis.voice.audio

import java.nio.file.Path

/** What a file is and how long it plays, before any decoding. */
class Probe(val format: Format, val durationSeconds: Double)

enum class Format { OGG, WAV }

object Decoder {
    /** Files longer than this stream frame by frame instead of being decoded into memory. */
    const val STREAM_THRESHOLD_SECONDS = 30.0

    fun formatOf(path: Path): Format {
        val name = path.fileName.toString().lowercase()
        return when {
            name.endsWith(".ogg") -> Format.OGG
            name.endsWith(".wav") || name.endsWith(".wave") -> Format.WAV
            else -> throw AudioDecodeException("unsupported audio format '${path.fileName}': only .ogg and .wav are supported")
        }
    }

    fun open(path: Path, format: Format = formatOf(path)): AudioStream = when (format) {
        Format.OGG -> OggStream.open(path)
        Format.WAV -> WavStream.open(path)
    }

    /** Duration is -1 when the header does not say; such files are decoded fully. */
    fun probe(path: Path): Probe {
        val format = formatOf(path)
        val duration = when (format) {
            Format.OGG -> OggStream.duration(path)
            Format.WAV -> WavStream.duration(path)
        }
        return Probe(format, duration)
    }

    fun frames(path: Path, format: Format = formatOf(path)): FramePipeline = FramePipeline(open(path, format))

    /** The whole file as consecutive 960-sample frames. */
    fun decodeAll(path: Path, format: Format = formatOf(path)): ShortArray {
        frames(path, format).use { pipe ->
            var out = ShortArray(SAMPLE_RATE)
            var n = 0
            while (true) {
                val f = pipe.nextFrame() ?: break
                if (n + f.size > out.size) out = out.copyOf(out.size * 2)
                System.arraycopy(f, 0, out, n, f.size)
                n += f.size
            }
            return out.copyOf(n)
        }
    }
}
