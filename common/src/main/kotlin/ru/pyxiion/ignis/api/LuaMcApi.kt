package ru.pyxiion.ignis.api

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.world.entity.Entity
import net.minecraft.world.SimpleContainer
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.Level
import org.luaj.vm2.*
import ru.pyxiion.ignis.*
import ru.pyxiion.ignis.api.Vector.Companion.toVec3d
import ru.pyxiion.ignis.api.manager.*
import ru.pyxiion.ignis.api.util.ItemBuilder
import ru.pyxiion.ignis.api.util.ItemStackCodec
import ru.pyxiion.ignis.api.wrapper.*
import ru.pyxiion.ignis.api.wrappertoLuaValue.PlayerListWrapper
import ru.pyxiion.ignis.storage.StorageManager
import java.nio.file.Path
import java.util.*

class LuaMcApi(
    private val server: MinecraftServer,
    private val storage: StorageManager,
    private val stateProvider: () -> LuaState,
    private val eventBus: EventBus,
) {
    val scheduler = Scheduler(stateProvider)
    private val playerCache = mutableMapOf<UUID, LuaValue>()

    init {
        EntityWrap.sharedPlayerCache = playerCache
        EntityWrap.sharedTickProvider = { scheduler.currentTick }
    }

    fun invalidatePlayer(uuid: UUID) {
        playerCache.remove(uuid)
    }

    fun clearPlayerCache() {
        playerCache.clear()
    }

    private fun requireWorld(arg: LuaValue): ServerLevel {
        return server.getLevel(requireWorldKey(arg))
            ?: throw IllegalArgumentException("Level ${arg.tostring()} not found.")
    }

    private fun requireWorldKey(arg: LuaValue): ResourceKey<Level> {
        val key = arg.checkjstring()
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(key))
    }


    private fun doBroadcast(text: String, pos: Vec3?, world: ResourceKey<Level>?, range: Double?, overlay: Int?) {
        var players = server.playerList.players

        range?.let { rng ->
            require(pos != null && world != null)
            val rangeSquare = rng * rng
            players = players.filter {
                it.level().dimension() == world
                        && it.distanceToSqr(pos) < rangeSquare
            }
        }

        if (overlay == null) {
            players.forEach {
                it.sendSystemMessage(Component.literal(text))
            }
        } else {
            val timingPacket = ClientboundSetTitlesAnimationPacket(20, overlay, 20)
            val titlePacket = ClientboundSetActionBarTextPacket(Component.literal(text))
            players.forEach { player ->
                player.connection.send(timingPacket)
                player.connection.send(titlePacket)
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

    private fun luaGetWorld(args: Varargs): Varargs {
        val name = args.arg(1).checkjstring()
        val key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(name))
        val world = server.getLevel(key)
            ?: throw IllegalArgumentException("Level '$name' not found")
        return WorldWrap.wrap(world, playerCache, { scheduler.currentTick })
    }

    private fun luaLoadStructure(args: Varargs): Varargs {
        val id = args.arg(1).checkjstring()
        val manager = server.structureManager
        val template = manager.get(Identifier.parse(id))
            .orElseThrow { LuaError("Структура '$id' не найдена") }
        return StructureWrap.wrap(template)
    }

    private fun luaLoadStructureFile(args: Varargs): Varargs {
        val path = args.arg(1).checkjstring()
        val nbt = NbtIo.readCompressed(Path.of(path), NbtAccounter.unlimitedHeap())
        val template = server.structureManager.readStructure(nbt)
        return StructureWrap.wrap(template)
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
        for (world in server.allLevels) {
            world.getEntity(uuid)?.let {
                return EntityFactory.wrap(it)
            }
        }
        return LuaValue.NIL
    }

    private fun luaGetRegion(args: Varargs): Varargs {
        require(args.narg() == 1) { "getRegion(id) requires 1 argument" }
        val id = args.arg(1).checkint()
        return RegionManager.get(id)?.let { RegionWrap.wrap(it) } ?: LuaValue.NIL
    }

    private fun buildSourceFromOpts(opts: LuaTable): CommandSourceStack {
        var source = server.createCommandSourceStack()
        opts.get("as").let { asVal ->
            if (asVal.istable()) {
                val entity = asVal.unwrapOrNull<Entity>()
                if (entity != null) {
                    source = source.withEntity(entity).withPosition(entity.position())
                }
            }
        }
        opts.get("at").let { atVal ->
            if (atVal.istable()) {
                val pos = atVal.toVec3d()
                source = source.withPosition(pos)
            }
        }
        return source
    }

    private fun luaExecute(args: Varargs): Varargs {
        val cmd = args.arg(1).checkjstring()

        val source = if (args.narg() >= 2 && args.arg(2).istable()) {
            buildSourceFromOpts(args.arg(2).checktable())
        } else {
            server.createCommandSourceStack()
        }

        return try {
            val result = server.commands.dispatcher.execute(cmd, source)
            LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(result))
        } catch (e: CommandSyntaxException) {
            LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.message ?: "Syntax error"))
        } catch (e: Exception) {
            LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(e.message ?: "Unknown error"))
        }
    }

    fun toTable(): LuaTable {
        MetaTableRegistry.init()

        val mcMeta = LuaTable()
        mcMeta.rawset("__index", luaFunction { _, key ->
            val k = key.checkjstring()
            when (k) {
                "onlineCount" -> {
                    server.playerList.playerCount.toLua()
                }

                "players" -> {
                    PlayerListWrapper(
                        source = { server.playerList.players },
                        playerCache = playerCache,
                        tickProvider = { scheduler.currentTick },
                    ).toLua()
                }

                else -> {
                    LuaValue.NIL
                }
            }
        })

        MetaTableRegistry.ITEM.set("serialise", luaFunction { self ->
            val stack = ItemStackWrap.unwrap(self)
                ?: throw LuaError("item:serialise(): не ItemStack")
            val json = ItemStackCodec.encode(stack, server.registryAccess())
            json.toLua()
        })

        MetaTableRegistry.INVENTORY.set("serialise", luaFunction { self ->
            val inv = self.unwrap<SimpleContainer>()
            val json = InvWrap.serialise(inv, server.registryAccess())
            json.toLua()
        })

        val table = luaTableOf(
            "broadcast" to this::broadcast.asVarArgFunction(),
            "data" to storage.getGlobalData(),
            "time" to this::luaTime.asVarArgFunction(),
            "schedule" to this::luaSchedule.asVarArgFunction(),
            "scheduleRepeating" to this::luaScheduleRepeating.asVarArgFunction(),
            "cancelTask" to this::luaCancelTask.asVarArgFunction(),
            "world" to this::luaGetWorld.asVarArgFunction(),
            "getEntity" to this::luaGetEntity.asVarArgFunction(),
            "getRegion" to this::luaGetRegion.asVarArgFunction(),
            "holograms" to luaFunctionZero { HologramManager.all().map { it.toLuaValue() }.toLuaArray() },
            "getHologram" to luaFunction { a ->
                val uuid = UUID.fromString(a.checkjstring())
                HologramManager.get(uuid)?.toLuaValue().orNil()
            },
            "loadStructure" to this::luaLoadStructure.asVarArgFunction(),
            "loadStructureFile" to this::luaLoadStructureFile.asVarArgFunction(),
            "dump" to this::luaDump.asVarArgFunction(),
            "getMetatable" to this::luaGetMetatable.asVarArgFunction(),
            "serialise" to luaFunction { type, obj ->
                when (val t = type.checkjstring()) {
                    "item" -> {
                        val stack = ItemStackWrap.unwrap(obj)
                            ?: throw LuaError("mc.serialise('item', ...): ожидается ItemStack")
                        val json = ItemStackCodec.encode(stack, server.registryAccess())
                        LuaValue.valueOf(json)
                    }

                    "inventory" -> {
                        val inv = obj.unwrap<SimpleContainer>()
                        val json = InvWrap.serialise(inv, server.registryAccess())
                        LuaValue.valueOf(json)
                    }

                    else -> throw LuaError("mc.serialise: неизвестный тип '$t'")
                }
            },
            "deserialise" to luaFunction { type, data ->
                val t = type.checkjstring()
                val d = data.checkjstring()
                when (t) {
                    "item" -> {
                        val stack = ItemStackCodec.decode(d, server.registryAccess())
                        if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
                    }

                    "inventory" -> {
                        val inv = InvWrap.deserialise(d, server.registryAccess())
                        InvWrap.wrap(inv)
                    }

                    else -> throw LuaError("mc.deserialise: неизвестный тип '$t'")
                }
            },
            "createInventory" to luaFunction { size ->
                val s = size.checkint()
                if (s !in 9..54 || s % 9 != 0)
                    throw LuaError("mc.createInventory: размер должен быть кратен 9 и от 9 до 54")
                InvWrap.wrap(LockableInventory(s))
            },

            "registerBehaviour" to luaFunction { id, fn ->
                val i = id.checkjstring()
                val f = fn.checkfunction()
                MobAIManager.registerBehaviour(i, f)
                LuaValue.NIL
            },
            "createBossBar" to luaFunction { title, color, style ->
                val wrapper =
                    BossBarWrapper(title.checkjstring(), color.optjstring("white"), style.optjstring("progress"))
                BossBarManager.register(wrapper)
                wrapper.toLuaValue()
            },
            "execute" to this::luaExecute.asVarArgFunction(),
        )

        table.set("on", luaFunction { eventName, handler ->
            eventBus.on(eventName.checkjstring(), handler.checkfunction()).toLua()
        })

        table.set("off", luaFunction { id ->
            eventBus.off(id.checkint()).toLua()
        })

        table.set("emit", luaVarFunction { args ->
            require(args.narg() >= 1) { "emit(event, ...) requires at least 1 argument" }
            val eventName = args.checkjstring(1)
            val eventArgs = if (args.narg() >= 2) {
                (2..args.narg()).map { args.arg(it) }.toTypedArray()
            } else emptyArray<LuaValue>()
            eventBus.fire(eventName, *eventArgs)
            LuaValue.NIL
        })

        table.set("createItem", luaVarFunction { args ->
            val stack = ItemBuilder.fromLua(args)
            ItemStackWrap.wrap(stack)
        })

        AsyncLib(server, stateProvider(), scheduler).install(table)

        table.setmetatable(mcMeta)
        return table
    }
}