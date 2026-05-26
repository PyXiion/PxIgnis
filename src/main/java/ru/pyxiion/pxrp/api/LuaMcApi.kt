package ru.pyxiion.pxrp.api

import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.structure.StructureTemplate
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import ru.pyxiion.pxrp.Scheduler
import ru.pyxiion.pxrp.asVarArgFunction
import ru.pyxiion.pxrp.luaTableOf
import ru.pyxiion.pxrp.storage.StorageManager
import ru.pyxiion.pxrp.api.EntityWrapper
import java.nio.file.Path
import java.util.HashSet
import java.util.UUID

class LuaMcApi(
    private val server: MinecraftServer,
    private val storage: StorageManager
) {
    val scheduler = Scheduler()
    private val playerCache = mutableMapOf<UUID, LuaValue>()

    fun invalidatePlayer(uuid: UUID) {
        playerCache.remove(uuid)
    }
    private fun <T> getRegistryKey(registryType: RegistryKey<Registry<T>>, key: String): RegistryKey<T> {
        return RegistryKey.of(registryType, Identifier.of(key))
    }

    private fun requireWorld(arg: LuaValue): ServerWorld {
        return server.getWorld(requireWorldKey(arg))
            ?: throw IllegalArgumentException("World ${arg.tostring()} not found.")
    }

    private fun requireWorldKey(arg: LuaValue): RegistryKey<World> {
        val key = arg.checkjstring()
        return getRegistryKey(RegistryKeys.WORLD, key)
    }


    private fun requireSound(arg: LuaValue): SoundEvent {
        val key = arg.checkjstring()
        return Registries.SOUND_EVENT.get(getRegistryKey(RegistryKeys.SOUND_EVENT, key))
            ?: throw IllegalArgumentException("Sound $key not found")
    }


    private fun playSound(args: Varargs) {
        require(args.narg() in 5..7) { "playSound(sound, x, y, z, world, volume = 1, pitch[0-2] = 1) requires 5..7 arguments" }
        val sound = requireSound(args.arg(1))
        val (x, y, z) = (2..4).map { args.arg(it).checkdouble() }
        val world = requireWorld(args.arg(5))
        val (volume, pitch) = (6..7).map { args.arg(it).optdouble(1.0).toFloat() }

        world.playSound(
            null,
            x, y, z,
            sound,
            SoundCategory.PLAYERS,
            volume,
            pitch
        )
    }

    private fun doBroadcast(text: String, pos: Vec3d?, world: RegistryKey<World>?, range: Double?, overlay: Int?) {
        var players = server.playerManager.playerList

        range?.let { rng ->
            require(pos != null && world != null)
            val rangeSquare = rng * rng
            players = players.filter {
                it.entityWorld.registryKey == world
                        && it.squaredDistanceTo(pos) < rangeSquare
            }
        }

        if (overlay == null) {
            players.forEach {
                it.sendMessage(Text.literal(text))
            }
        } else {
            val timingPacket = TitleFadeS2CPacket(20, overlay, 20)
            val titlePacket = OverlayMessageS2CPacket(Text.literal(text))
            players.forEach {
                with(it.networkHandler) {
                    sendPacket(timingPacket)
                    sendPacket(titlePacket)
                }
            }
        }
    }

    private fun luaTime(args: Varargs): Varargs {
        return LuaValue.valueOf(System.currentTimeMillis() / 1000.0)
    }

    private fun broadcast(args: Varargs) {
        require(args.narg() in 1..2) { "broadcast(text, overlay = false) requires 1..2 arguments" }
        val text = args.arg(1).checkjstring()
        val overlay = if (args.arg(2).isint()) args.arg(2).toint() else null

        doBroadcast(text, null, null, null, overlay)
    }

    private fun luaSchedule(args: Varargs): Varargs {
        require(args.narg() == 2) { "schedule(delay, callback) requires 2 arguments" }
        val delay = args.arg(1).checkint()
        val callback = args.arg(2).checkfunction()
        val id = scheduler.schedule(delay, callback)
        return LuaValue.valueOf(id)
    }

    private fun luaScheduleRepeating(args: Varargs): Varargs {
        require(args.narg() == 3) { "scheduleRepeating(delay, interval, callback) requires 3 arguments" }
        val delay = args.arg(1).checkint()
        val interval = args.arg(2).checkint()
        val callback = args.arg(3).checkfunction()
        val id = scheduler.scheduleRepeating(delay, interval, callback)
        return LuaValue.valueOf(id)
    }

    private fun luaCancelTask(args: Varargs): Varargs {
        require(args.narg() == 1) { "cancelTask(id) requires 1 argument" }
        val id = args.arg(1).checkint()
        val removed = scheduler.cancel(id)
        return LuaValue.valueOf(removed)
    }

    private fun luaGetPlayers(args: Varargs): Varargs {
        val list = LuaTable()
        server.playerManager.playerList.forEachIndexed { i, p ->
            list.set(i + 1, playerCache.getOrPut(p.uuid) { Player(p).toLuaValue() })
        }
        return list
    }

    private fun luaGetWorld(args: Varargs): Varargs {
        val name = args.arg(1).checkjstring()
        val key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(name))
        val world = server.getWorld(key)
            ?: throw IllegalArgumentException("World '$name' not found")
        return World(world, playerCache).toLuaValue()
    }

    private fun luaLoadStructure(args: Varargs): Varargs {
        val id = args.arg(1).checkjstring()
        val manager = server.structureTemplateManager
        val template = manager.getTemplate(Identifier.of(id))
            .orElseThrow { LuaError("Структура '$id' не найдена") }
        return StructureWrapper(template, server).toLuaValue()
    }

    private fun luaLoadStructureFile(args: Varargs): Varargs {
        val path = args.arg(1).checkjstring()
        val nbt = NbtIo.readCompressed(Path.of(path), NbtSizeTracker.ofUnlimitedBytes())
        val template = server.structureTemplateManager.createTemplate(nbt)
        return StructureWrapper(template, server).toLuaValue()
    }

    private fun luaGetMetatable(args: Varargs): Varargs {
        val name = args.arg(1).checkjstring()
        return MetaTableRegistry.get(name)
    }

    private fun luaDump(args: Varargs): Varargs {
        val maxDepth = args.arg(2).optint(3)
        val sb = StringBuilder()
        val seen = HashSet<Int>()
        dumpValue(args.arg(1), sb, 0, maxDepth, seen)
        println(sb.toString())
        return LuaValue.valueOf(sb.toString())
    }

    private fun dumpValue(value: LuaValue, sb: StringBuilder, depth: Int, maxDepth: Int, seen: HashSet<Int>) {
        if (depth > maxDepth) {
            sb.append("...")
            return
        }
        when {
            value.isnil() -> sb.append("nil")
            value.isboolean() -> sb.append(value.toboolean())
            value.isnumber() -> sb.append(value.todouble())
            value.isstring() -> {
                sb.append('"')
                sb.append(value.tojstring())
                sb.append('"')
            }
            value.istable() -> {
                val table = value.checktable()
                val hash = System.identityHashCode(table)
                if (hash in seen) {
                    sb.append("{...}")
                    return
                }
                seen.add(hash)
                sb.append("{\n")
                val indent = "  ".repeat(depth + 1)

                val meta = table.getmetatable()
                val pairsFn = meta?.get("__pairs")

                if (pairsFn != null && pairsFn.isfunction()) {
                    val triple = pairsFn.invoke(LuaValue.varargsOf(arrayOf(table)))
                    val iter = triple.arg(1)
                    val state = triple.arg(2)
                    var key = triple.arg(3)
                    while (true) {
                        val next = iter.invoke(LuaValue.varargsOf(arrayOf(state, key)))
                        val nextKey = next.arg(1)
                        if (nextKey.isnil()) break
                        key = nextKey
                        val v = next.arg(2)
                        sb.append(indent)
                        if (key.isstring()) {
                            sb.append(key.tojstring())
                        } else {
                            sb.append('[')
                            dumpValue(key, sb, depth, maxDepth, seen)
                            sb.append(']')
                        }
                        sb.append(" = ")
                        dumpValue(v, sb, depth + 1, maxDepth, seen)
                        sb.append(",\n")
                    }
                } else {
                    var key = LuaValue.NIL
                    while (true) {
                        val next = table.next(key)
                        if (next.arg(1).isnil()) break
                        key = next.arg(1)
                        val v = next.arg(2)
                        sb.append(indent)
                        if (key.isstring()) {
                            sb.append(key.tojstring())
                        } else {
                            sb.append('[')
                            dumpValue(key, sb, depth, maxDepth, seen)
                            sb.append(']')
                        }
                        sb.append(" = ")
                        dumpValue(v, sb, depth + 1, maxDepth, seen)
                        sb.append(",\n")
                    }
                }

                sb.append("  ".repeat(depth))
                sb.append('}')
            }
            value.isfunction() -> sb.append("function")
            value.isuserdata() -> sb.append("userdata")
            value.isthread() -> sb.append("thread")
            else -> sb.append(value.typename())
        }
    }

    private fun luaGetEntity(args: Varargs): Varargs {
        val uuid = UUID.fromString(args.arg(1).checkjstring())
        for (world in server.worlds) {
            world.getEntity(uuid)?.let { return EntityWrapper(it).toLuaValue() }
        }
        return LuaValue.NIL
    }

    fun toTable(): LuaTable {
        return luaTableOf(
            "broadcast" to this::broadcast.asVarArgFunction(),
            "playSound" to this::playSound.asVarArgFunction(),
            "data" to storage.getGlobalData(),
            "time" to this::luaTime.asVarArgFunction(),
            "schedule" to this::luaSchedule.asVarArgFunction(),
            "scheduleRepeating" to this::luaScheduleRepeating.asVarArgFunction(),
            "cancelTask" to this::luaCancelTask.asVarArgFunction(),
            "world" to this::luaGetWorld.asVarArgFunction(),
            "players" to this::luaGetPlayers.asVarArgFunction(),
            "getEntity" to this::luaGetEntity.asVarArgFunction(),
            "onlineCount" to LuaValue.valueOf(server.playerManager.currentPlayerCount.toDouble()),
            "loadStructure" to this::luaLoadStructure.asVarArgFunction(),
            "loadStructureFile" to this::luaLoadStructureFile.asVarArgFunction(),
            "dump" to this::luaDump.asVarArgFunction(),
            "getMetatable" to this::luaGetMetatable.asVarArgFunction(),
        )
    }
}