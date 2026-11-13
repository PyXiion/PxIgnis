package ru.pyxiion.ignis.voice

import net.minecraft.world.entity.Entity
import ru.pyxiion.ignis.voice.audio.FRAME_SECONDS
import ru.pyxiion.luanova.vm2.LuaFunction
import java.util.UUID

/** Who hears an emitter besides range: a function evaluated per online player, or a fixed set. */
sealed interface Listeners {
    class Fn(val fn: LuaFunction) : Listeners
    class Fixed(val ids: Set<UUID>) : Listeners
}

/**
 * A long-lived source with a track queue. State is guarded by [lock]: Lua calls come in on the server thread, frames
 * are pulled by the backend threads. Lua callbacks leave through [VoiceEnv.runOnServer] only.
 */
class Emitter(
    private val env: VoiceEnv,
    private val backends: List<VoiceBackend>,
    private var source: Source,
    val distance: Float,
    val category: String?,
    volume: Float,
    private val listeners: Listeners?,
    private val closeOnEnd: Boolean,
) {
    private class Entry(val sound: Sound, val loop: Boolean)

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private var cursor: FrameCursor? = null
    private var entry: Entry? = null
    private var running = false
    private var waiting = false // the head of the queue is still decoding
    private var trackFrames = 0L
    private var generation = 0

    // Frames already produced for the current run, so several channels (one per backend) can each read them.
    private val ring = arrayOfNulls<ShortArray>(RING)
    private var produced = 0L

    private var channels: List<Channel> = emptyList()
    private var closed = false

    @Volatile
    var volume: Float = volume

    var onEnd: LuaFunction? = null

    val playing: Boolean get() = synchronized(lock) { running }
    val positionSeconds: Double get() = synchronized(lock) { if (running) trackFrames * FRAME_SECONDS else 0.0 }
    val entity: Entity? get() = (source as? Source.OfEntity)?.entity
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Channels open when playback first starts, not at creation: scripts run before the voice chat server is up.
     * Channels that a backend could not open yet are retried by [refresh].
     */
    private fun ensureChannels() {
        if (channels.isNotEmpty() || backends.isEmpty()) return
        val audience = audience()
        channels = backends.mapNotNull { it.openChannel(source, distance, category, audience) }
    }

    fun play(sound: Sound, loop: Boolean) {
        if (sound.error != null) throw IllegalStateException("sound '${sound.name}' failed to load: ${sound.error}")
        synchronized(lock) {
            check(!closed) { "emitter is closed" }
            resetLocked()
            queue.addLast(Entry(sound, loop))
        }
        arm(sound)
    }

    fun queue(sound: Sound) {
        if (sound.error != null) throw IllegalStateException("sound '${sound.name}' failed to load: ${sound.error}")
        synchronized(lock) {
            check(!closed) { "emitter is closed" }
            queue.addLast(Entry(sound, false))
        }
        arm(sound)
    }

    private fun arm(sound: Sound) {
        sound.whenSettled { kick() }
    }

    /** Starts playback if it is idle and the head of the queue is decoded. Server thread. */
    fun kick() {
        synchronized(lock) {
            if (closed || running) return
            while (queue.isNotEmpty() && queue.first().sound.error != null) queue.removeFirst()
            val head = queue.firstOrNull() ?: return
            if (head.sound.data == null) {
                waiting = true
                return
            }
            ensureChannels()
            if (channels.isEmpty()) return
            waiting = false
            generation++
            produced = 0
            ring.fill(null)
            running = true
            val gen = generation
            channels.forEach { it.start(Feed(gen, 0)) }
        }
    }

    fun stop() {
        synchronized(lock) {
            resetLocked()
            running = false
            generation++
        }
        channels.forEach { it.stop() }
    }

    fun close() {
        stop()
        synchronized(lock) { closed = true }
        channels.forEach { it.close() }
        channels = emptyList()
        env.forget(this)
    }

    private fun resetLocked() {
        queue.clear()
        cursor?.close()
        cursor = null
        entry = null
        trackFrames = 0
        waiting = false
    }

    fun setEntity(e: Entity) {
        check(source is Source.OfEntity) { "only an emitter created with an entity can change it" }
        source = Source.OfEntity(e)
        pushUpdate()
    }

    /** About once a second, on the server thread: entity removal and the audience. */
    fun refresh() {
        val e = entity
        if (e != null && e.isRemoved) stop()
        pushUpdate()
        kick() // a backend that was not ready when play() was called
    }

    private fun pushUpdate() {
        val audience = audience()
        channels.forEach { it.update(source, audience) }
    }

    private fun audience(): Set<UUID>? {
        val s = source
        val base: Collection<net.minecraft.server.level.ServerPlayer> =
            if (s is Source.OfPlayers) s.players else env.onlinePlayers()
        return when (listeners) {
            null -> if (s is Source.OfPlayers) base.mapTo(HashSet()) { it.uuid } else null
            is Listeners.Fixed -> base.filterTo(ArrayList()) { it.uuid in listeners.ids }.mapTo(HashSet()) { it.uuid }
            is Listeners.Fn -> base.filter { env.callBoolean("voice listeners", listeners.fn, it) }.mapTo(HashSet()) { it.uuid }
        }
    }

    private inner class Feed(private val gen: Int, private var idx: Long) : FrameSource {
        override fun next(): ShortArray? {
            var ended = false
            val frame = synchronized(lock) {
                if (gen != generation) return null
                if (idx < produced - RING + 1) idx = produced - RING + 1
                if (idx < produced) {
                    ring[(idx++ % RING).toInt()]
                } else {
                    val f = produceLocked()
                    if (f == null) {
                        running = false
                        ended = !waiting
                        generation++
                        null
                    } else {
                        ring[(produced % RING).toInt()] = f
                        produced++
                        idx++
                        f
                    }
                }
            }
            if (ended) env.runOnServer { onQueueEnded() }
            return frame
        }
    }

    private fun onQueueEnded() {
        onEnd?.let { env.call("emitter onEnd", it) }
        if (closeOnEnd) close()
    }

    private fun produceLocked(): ShortArray? {
        while (true) {
            var c = cursor
            if (c == null) {
                while (queue.isNotEmpty() && queue.first().sound.error != null) queue.removeFirst()
                val head = queue.firstOrNull() ?: return null
                val data = head.sound.data
                if (data == null) {
                    waiting = true
                    return null
                }
                queue.removeFirst()
                entry = head
                trackFrames = 0
                c = data.cursor()
                cursor = c
            }
            val f = c.next()
            if (f != null) {
                trackFrames++
                return scale(f)
            }
            c.close()
            cursor = null
            val e = entry
            if (e != null && e.loop && trackFrames > 0) {
                queue.addFirst(e)
            }
            trackFrames = 0
        }
    }

    private fun scale(f: ShortArray): ShortArray {
        val v = volume
        if (v == 1f) return f
        val out = ShortArray(f.size)
        for (i in f.indices) out[i] = (f[i] * v).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }

    private companion object {
        const val RING = 16
    }
}
