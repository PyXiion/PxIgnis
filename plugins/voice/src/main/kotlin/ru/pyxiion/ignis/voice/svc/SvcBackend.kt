package ru.pyxiion.ignis.voice.svc

import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import ru.pyxiion.ignis.voice.Channel
import ru.pyxiion.ignis.voice.FrameSource
import ru.pyxiion.ignis.voice.Source
import ru.pyxiion.ignis.voice.VoiceBackend
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Simple Voice Chat. SVC owns one server API for the whole process, so this is a singleton that [SvcPlugin] feeds.
 * Only referenced when the `voicechat` mod is loaded.
 */
object SvcBackend : VoiceBackend {
    private val logger = LoggerFactory.getLogger("ignis-voice")

    private class Category(val id: String, val name: String, val description: String, val icon: IntArray2D?)

    private val categories = ConcurrentHashMap<String, Category>()

    /** Set while the voice server runs (between its started and stopped events). Categories declared before that are registered when it arrives. */
    @Volatile
    var api: VoicechatServerApi? = null
        set(value) {
            field = value
            if (value != null) categories.values.forEach { register(value, it) }
        }

    override val id = "svc"
    override val installed = true

    override fun canHear(player: ServerPlayer): Boolean {
        val c = api?.getConnectionOf(player.uuid) ?: return false
        return c.isConnected && !c.isDisabled
    }

    override fun registerCategory(id: String, name: String, description: String, icon: BufferedImage?) {
        val category = Category(id, name, description, icon?.let(::toArgb16))
        categories[id] = category
        api?.let { register(it, category) }
    }

    override fun unregisterCategory(id: String) {
        categories.remove(id)
        api?.unregisterVolumeCategory(id)
    }

    private fun register(api: VoicechatServerApi, c: Category) {
        // SVC rejects a duplicate id: replace it, so a reload picks up a changed name or icon.
        api.unregisterVolumeCategory(c.id)
        val b = api.volumeCategoryBuilder().setId(c.id).setName(c.name).setDescription(c.description)
        c.icon?.let { b.setIcon(it) }
        api.registerVolumeCategory(b.build())
    }

    override fun openChannel(source: Source, distance: Float, category: String?, audience: Set<UUID>?): Channel? {
        val api = api ?: return null
        return try {
            SvcChannel(api, source, distance, category, audience)
        } catch (e: IllegalStateException) {
            logger.debug("Simple Voice Chat is not ready for a channel yet: {}", e.message)
            null
        }
    }

    /** SVC wants the icon as `[x][y]` ARGB, exactly 16x16. */
    private fun toArgb16(image: BufferedImage): IntArray2D {
        val scaled = if (image.width == 16 && image.height == 16) image else {
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(image, 0, 0, 16, 16, null)
                g.dispose()
            }
        }
        return Array(16) { x -> IntArray(16) { y -> scaled.getRGB(x, y) } }
    }

    private class SvcChannel(
        private val api: VoicechatServerApi,
        source: Source,
        distance: Float,
        category: String?,
        audience: Set<UUID>?,
    ) : Channel {
        @Volatile
        private var audience: Set<UUID>? = audience
        private val channel: AudioChannel
        private var player: AudioPlayer? = null
        private var targets = emptySet<UUID>() // static channels send to explicit connections

        init {
            val id = UUID.randomUUID()
            val created: AudioChannel? = when (source) {
                is Source.OfEntity -> api.createEntityAudioChannel(id, api.fromEntity(source.entity))?.also { it.distance = distance }
                is Source.OfPos -> api.createLocationalAudioChannel(
                    id, api.fromServerLevel(source.level), api.createPosition(source.x, source.y, source.z),
                )?.also { it.distance = distance }
                is Source.OfPlayers -> api.createStaticAudioChannel(id)
            }
            channel = checkNotNull(created) { "Simple Voice Chat refused to create a channel" }
            category?.let { channel.category = it }
            if (source !is Source.OfPlayers) {
                channel.setFilter { p ->
                    val allowed = this.audience
                    allowed == null || p.uuid in allowed
                }
            }
            update(source, audience)
        }

        override fun start(frames: FrameSource) {
            stop()
            // The player closes its encoder when it stops, so every player gets a fresh one.
            val p = api.createAudioPlayer(channel, api.createEncoder(), Supplier { frames.next() })
            player = p
            p.startPlaying()
        }

        override fun stop() {
            player?.stopPlaying()
            player = null
        }

        override fun update(source: Source, audience: Set<UUID>?) {
            this.audience = audience
            when (source) {
                is Source.OfEntity -> (channel as EntityAudioChannel).updateEntity(api.fromEntity(source.entity))
                is Source.OfPos -> (channel as LocationalAudioChannel).updateLocation(api.createPosition(source.x, source.y, source.z))
                is Source.OfPlayers -> updateTargets(channel as StaticAudioChannel, audience.orEmpty())
            }
        }

        private fun updateTargets(c: StaticAudioChannel, want: Set<UUID>) {
            for (gone in targets - want) api.getConnectionOf(gone)?.let { c.removeTarget(it) }
            val kept = HashSet<UUID>()
            for (uuid in want) {
                val conn = api.getConnectionOf(uuid)
                if (conn == null) continue
                if (uuid !in targets) c.addTarget(conn)
                kept.add(uuid)
            }
            targets = kept
        }

        override fun close() {
            stop()
            channel.flush()
        }
    }
}

private typealias IntArray2D = Array<IntArray>
