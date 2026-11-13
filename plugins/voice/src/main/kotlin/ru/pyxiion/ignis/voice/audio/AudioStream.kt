package ru.pyxiion.ignis.voice.audio

import java.io.Closeable

/** Decoded PCM as interleaved floats in [-1, 1], at the file's own rate and channel count. */
interface AudioStream : Closeable {
    val rate: Int
    val channels: Int

    /** Fills [buf] with whole sample frames and returns how many (at least 1), or -1 at the end of the stream. */
    fun read(buf: FloatArray): Int
}

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
