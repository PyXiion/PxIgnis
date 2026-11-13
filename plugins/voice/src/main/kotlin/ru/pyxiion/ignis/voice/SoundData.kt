package ru.pyxiion.ignis.voice

import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.voice.audio.Decoder
import ru.pyxiion.ignis.voice.audio.FRAME_SAMPLES
import ru.pyxiion.ignis.voice.audio.FramePipeline
import ru.pyxiion.ignis.voice.audio.Format
import ru.pyxiion.ignis.voice.audio.SAMPLE_RATE
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/** One playback position in a sound. [next] returns consecutive 960-sample frames, then null. */
interface FrameCursor : Closeable {
    fun next(): ShortArray?
}

/**
 * A decoded sound shared by every [Sound] handle of that file version: either the whole PCM in memory
 * (short files) or the file path to stream from (long files).
 */
class SoundData private constructor(
    val durationSeconds: Double,
    private val pcm: ShortArray?,
    private val path: Path,
    private val format: Format,
) {
    /** Heap held by this entry, for the cache limit. */
    val bytes: Long get() = (pcm?.size ?: 0) * 2L

    fun cursor(): FrameCursor = if (pcm != null) MemoryCursor(pcm) else StreamCursor(path, format)

    private class MemoryCursor(private val pcm: ShortArray) : FrameCursor {
        private var pos = 0

        override fun next(): ShortArray? {
            if (pos >= pcm.size) return null
            val frame = ShortArray(FRAME_SAMPLES)
            val n = minOf(FRAME_SAMPLES, pcm.size - pos)
            System.arraycopy(pcm, pos, frame, 0, n)
            pos += n
            return frame
        }

        override fun close() {}
    }

    /** Decodes on the thread that pulls frames; the file is opened at the first frame. */
    private class StreamCursor(private val path: Path, private val format: Format) : FrameCursor {
        private var pipe: FramePipeline? = null
        private var failed = false

        override fun next(): ShortArray? {
            if (failed) return null
            return try {
                val p = pipe ?: Decoder.frames(path, format).also { pipe = it }
                p.nextFrame()
            } catch (e: Exception) {
                failed = true
                logger.warn("Streaming '{}' failed: {}", path.fileName, e.message)
                null
            }
        }

        override fun close() {
            pipe?.close()
            pipe = null
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("ignis-voice")

        /** Blocking: reads the file. Call off the server thread. */
        fun decode(path: Path): SoundData {
            val probe = Decoder.probe(path)
            if (probe.durationSeconds > Decoder.STREAM_THRESHOLD_SECONDS) {
                // Open once now so a broken file fails at load(), not in the middle of playback.
                Decoder.open(path, probe.format).close()
                return SoundData(probe.durationSeconds, null, path, probe.format)
            }
            val pcm = Decoder.decodeAll(path, probe.format)
            return SoundData(pcm.size.toDouble() / SAMPLE_RATE, pcm, path, probe.format)
        }

        fun ofPcm(pcm: ShortArray, path: Path = Path.of("memory")): SoundData =
            SoundData(pcm.size.toDouble() / SAMPLE_RATE, pcm, path, Format.WAV)
    }
}

/** Identifies a file version: a changed file is a new entry. */
data class SoundKey(val path: Path, val mtime: Long, val size: Long) {
    companion object {
        fun of(path: Path): SoundKey {
            val real = path.toAbsolutePath().normalize()
            return SoundKey(real, Files.getLastModifiedTime(real).toMillis(), Files.size(real))
        }
    }
}

/** Decoded-audio cache with a memory limit, evicting the least recently used. Thread-safe. */
class SoundCache(private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val entries = LinkedHashMap<SoundKey, SoundData>(16, 0.75f, true)
    private var total = 0L

    @Synchronized
    operator fun get(key: SoundKey): SoundData? = entries[key]

    @Synchronized
    fun put(key: SoundKey, data: SoundData) {
        entries.put(key, data)?.let { total -= it.bytes }
        total += data.bytes
        val it = entries.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.value === data) continue
            total -= e.value.bytes
            it.remove()
        }
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024
    }
}
