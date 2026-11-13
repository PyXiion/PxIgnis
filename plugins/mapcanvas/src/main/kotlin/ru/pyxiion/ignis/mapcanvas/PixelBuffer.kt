package ru.pyxiion.ignis.mapcanvas

/** The rectangle of [bytes] (row-major, [w] x [h]) that starts at ([x], [y]) on the canvas. */
class Patch(val x: Int, val y: Int, val w: Int, val h: Int, val bytes: ByteArray)

/** A 128x128 palette-byte canvas that remembers the bounding rectangle of pixels changed since the last [takeDirty]. */
class PixelBuffer {
    val data = ByteArray(SIZE * SIZE)

    private var minX = SIZE
    private var minY = SIZE
    private var maxX = -1
    private var maxY = -1

    val dirty: Boolean get() = maxX >= 0

    private fun mark(x: Int, y: Int) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    private fun set(x: Int, y: Int, c: Byte) {
        val i = y * SIZE + x
        if (data[i] != c) {
            data[i] = c
            mark(x, y)
        }
    }

    fun pixel(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return
        set(x, y, color.toByte())
    }

    fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        val x0 = maxOf(x.toLong(), 0L).toInt()
        val y0 = maxOf(y.toLong(), 0L).toInt()
        val x1 = minOf(x.toLong() + w, SIZE.toLong()).toInt()
        val y1 = minOf(y.toLong() + h, SIZE.toLong()).toInt()
        val c = color.toByte()
        for (yy in y0 until y1) for (xx in x0 until x1) set(xx, yy, c)
    }

    fun clear(color: Int) = fill(0, 0, SIZE, SIZE, color)

    /** Copies [w] x [h] bytes from [src] (starting at [srcOffset], row-major, tightly packed); the part outside the canvas is skipped. */
    fun blit(x: Int, y: Int, w: Int, h: Int, src: ByteArray, srcOffset: Int = 0) {
        require(w >= 0 && h >= 0) { "negative size" }
        require(w.toLong() * h <= src.size - srcOffset) { "blit: need ${w.toLong() * h} bytes for ${w}x$h, got ${src.size - srcOffset}" }
        for (row in 0 until h) {
            val yy = y + row
            if (yy < 0 || yy >= SIZE) continue
            for (col in 0 until w) {
                val xx = x + col
                if (xx < 0 || xx >= SIZE) continue
                set(xx, yy, src[srcOffset + row * w + col])
            }
        }
    }

    /** The changed rectangle (and resets the tracking), or null when nothing changed. */
    fun takeDirty(): Patch? {
        if (!dirty) return null
        val p = region(minX, minY, maxX - minX + 1, maxY - minY + 1)
        minX = SIZE; minY = SIZE; maxX = -1; maxY = -1
        return p
    }

    fun full(): Patch = region(0, 0, SIZE, SIZE)

    private fun region(x: Int, y: Int, w: Int, h: Int): Patch {
        val out = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(data, (y + row) * SIZE + x, out, row * w, w)
        return Patch(x, y, w, h, out)
    }

    companion object {
        const val SIZE = 128
    }
}
