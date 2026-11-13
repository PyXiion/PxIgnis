package ru.pyxiion.ignis.voice

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import java.awt.image.BufferedImage
import java.util.UUID

/** Where a sound comes from. */
sealed interface Source {
    class OfEntity(val entity: Entity) : Source
    class OfPos(val level: ServerLevel, val x: Double, val y: Double, val z: Double) : Source

    /** Non-spatial: the sound is heard at full volume by these players. */
    class OfPlayers(val players: List<ServerPlayer>) : Source
}

/** The next 20 ms frame (960 samples, 48 kHz mono s16), or null when playback ends. Called on the backend's own thread. */
fun interface FrameSource {
    fun next(): ShortArray?
}

/**
 * A voice chat mod as a sink for server-generated audio. The format is the one SVC and Plasmo Voice share:
 * 48 kHz, mono, 16-bit, 20 ms frames. Methods are called on the server thread except [FrameSource.next].
 */
interface VoiceBackend {
    /** Short name used by `voice.backends`. */
    val id: String

    /** The mod is installed. It may still be starting up: [canHear] and channels need it to be running. */
    val installed: Boolean

    /** The player is connected to this backend's voice chat. */
    fun canHear(player: ServerPlayer): Boolean

    fun registerCategory(id: String, name: String, description: String, icon: BufferedImage?)
    fun unregisterCategory(id: String)

    /**
     * Opens a channel for [source]. [audience] limits who hears it (null: everyone in range); for
     * [Source.OfPlayers] it is the set of players that currently receive it.
     */
    fun openChannel(source: Source, distance: Float, category: String?, audience: Set<UUID>?): Channel?
}

interface Channel {
    /** Starts pulling frames from [frames] at real-time pace until it returns null or [stop] is called. */
    fun start(frames: FrameSource)

    fun stop()

    /** The source moved or the audience changed. [source] has the same kind as at [VoiceBackend.openChannel]. */
    fun update(source: Source, audience: Set<UUID>?)

    fun close()
}
