package ru.pyxiion.ignis.voice.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Ogg Vorbis through jorbis. Chained streams are not followed: the first logical stream is played. */
class OggStream private constructor(private val input: InputStream, private val name: String) : AudioStream {
    private val sync = SyncState()
    private val os = StreamState()
    private val page = Page()
    private val packet = Packet()
    private val info = Info()
    private val comment = Comment()
    private val dsp = DspState()
    private val block = Block(dsp)

    private val pcm = arrayOf(emptyArray<FloatArray>())
    private var offsets = IntArray(0)
    private var pending = FloatArray(0) // interleaved samples decoded but not yet handed out
    private var pendingPos = 0
    private var pendingLen = 0
    private var eof = false

    override var rate = 0
        private set
    override var channels = 0
        private set

    init {
        sync.init()
        readHeaders()
    }

    private fun feed(): Int {
        val index = sync.buffer(CHUNK)
        val n = input.read(sync.data, index, CHUNK)
        sync.wrote(maxOf(n, 0))
        return n
    }

    private fun readHeaders() {
        if (feed() <= 0 || sync.pageout(page) != 1) throw AudioDecodeException("$name is not an Ogg file")
        os.init(page.serialno())
        info.init()
        comment.init()
        os.pagein(page)
        if (os.packetout(packet) != 1 || info.synthesis_headerin(comment, packet) < 0) {
            throw AudioDecodeException("$name is not an Ogg Vorbis file")
        }
        var got = 0
        while (got < 2) {
            var r = sync.pageout(page)
            while (r != 0 && got < 2) {
                if (r == 1) {
                    os.pagein(page)
                    while (got < 2) {
                        val p = os.packetout(packet)
                        if (p == 0) break
                        if (p < 0 || info.synthesis_headerin(comment, packet) < 0) {
                            throw AudioDecodeException("corrupt Vorbis headers in $name")
                        }
                        got++
                    }
                }
                if (got < 2) r = sync.pageout(page)
            }
            if (got < 2 && feed() <= 0) throw AudioDecodeException("unexpected end of Vorbis headers in $name")
        }
        rate = info.rate
        channels = info.channels
        if (rate <= 0 || channels <= 0) throw AudioDecodeException("invalid Vorbis stream in $name")
        offsets = IntArray(channels)
        dsp.synthesis_init(info)
        block.init(dsp)
    }

    override fun read(buf: FloatArray): Int {
        while (pendingPos == pendingLen) {
            if (!decodeMore()) return -1
        }
        val n = minOf(buf.size / channels * channels, pendingLen - pendingPos)
        System.arraycopy(pending, pendingPos, buf, 0, n)
        pendingPos += n
        return n / channels
    }

    /** Decodes until at least one sample is pending; false at the end of the stream. */
    private fun decodeMore(): Boolean {
        pendingPos = 0
        pendingLen = 0
        while (pendingLen == 0) {
            if (eof) return false
            val r = sync.pageout(page)
            if (r == 0) {
                if (feed() <= 0) eof = true
                continue
            }
            if (r < 0) continue // a hole in the stream: skip it
            os.pagein(page)
            while (true) {
                val p = os.packetout(packet)
                if (p == 0) break
                if (p < 0) continue
                if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
                while (true) {
                    val samples = dsp.synthesis_pcmout(pcm, offsets)
                    if (samples <= 0) break
                    append(samples)
                    dsp.synthesis_read(samples)
                }
            }
            if (page.eos() != 0) eof = true
        }
        return true
    }

    private fun append(samples: Int) {
        val need = pendingLen + samples * channels
        if (pending.size < need) pending = pending.copyOf(maxOf(need, pending.size * 2))
        for (ch in 0 until channels) {
            val src = pcm[0][ch]
            val start = offsets[ch]
            var o = pendingLen + ch
            for (i in 0 until samples) {
                pending[o] = src[start + i]
                o += channels
            }
        }
        pendingLen = need
    }

    override fun close() = input.close()

    companion object {
        private const val CHUNK = 4096
        private const val TAIL = 65536

        fun open(path: Path): OggStream {
            val stream = try {
                Files.newInputStream(path).buffered(CHUNK * 4)
            } catch (e: IOException) {
                throw AudioDecodeException("cannot read ${path.fileName}: ${e.message}", e)
            }
            return try {
                OggStream(stream, path.fileName.toString())
            } catch (e: Throwable) {
                stream.close()
                if (e is AudioDecodeException) throw e
                throw AudioDecodeException("cannot decode ${path.fileName}: ${e.message}", e)
            }
        }

        /** Length in seconds: the sample rate from the header and the last page's granule position. -1 if unknown. */
        fun duration(path: Path): Double {
            val rate = open(path).use { it.rate }
            val granule = lastGranule(path)
            return if (granule < 0) -1.0 else granule / rate.toDouble()
        }

        internal fun lastGranule(path: Path): Long {
            try {
                Files.newByteChannel(path, StandardOpenOption.READ).use { ch ->
                    val size = ch.size()
                    val len = minOf(size, TAIL.toLong()).toInt()
                    val buf = java.nio.ByteBuffer.allocate(len)
                    ch.position(size - len)
                    while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
                    val a = buf.array()
                    var i = len - 27
                    while (i >= 0) {
                        if (a[i] == 'O'.code.toByte() && a[i + 1] == 'g'.code.toByte() && a[i + 2] == 'g'.code.toByte() &&
                            a[i + 3] == 'S'.code.toByte()
                        ) {
                            var g = 0L
                            for (k in 7 downTo 0) g = (g shl 8) or (a[i + 6 + k].toLong() and 0xFF)
                            if (g >= 0) return g
                        }
                        i--
                    }
                }
            } catch (e: IOException) {
                throw AudioDecodeException("cannot read ${path.fileName}: ${e.message}", e)
            }
            return -1
        }
    }
}
