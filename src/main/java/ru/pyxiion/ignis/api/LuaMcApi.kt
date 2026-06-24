package ru.pyxiion.ignis.api

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.inventory.SimpleInventory
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.entity.Entity
import com.mojang.brigadier.exceptions.CommandSyntaxException
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.ignis.EventBus
import ru.pyxiion.ignis.Scheduler
import ru.pyxiion.ignis.api.AsyncLib
import ru.pyxiion.ignis.api.manager.BossBarManager
import ru.pyxiion.ignis.api.manager.HologramManager
import ru.pyxiion.ignis.api.manager.LockableInventory
import ru.pyxiion.ignis.api.manager.MobAIManager
import ru.pyxiion.ignis.api.manager.RegionManager
import ru.pyxiion.ignis.api.util.ItemBuilder
import ru.pyxiion.ignis.api.util.ItemStackCodec
import ru.pyxiion.ignis.api.wrapper.BossBarWrapper
import ru.pyxiion.ignis.api.wrapper.EntityFactory
import ru.pyxiion.ignis.api.wrapper.InvWrap
import ru.pyxiion.ignis.api.wrapper.ItemStackWrap
import ru.pyxiion.ignis.api.wrapper.PlayerListWrapper
import ru.pyxiion.ignis.api.wrapper.RegionWrap
import ru.pyxiion.ignis.api.wrapper.StructureWrap
import ru.pyxiion.ignis.api.wrapper.WorldWrap
import ru.pyxiion.ignis.asVarArgFunction
import ru.pyxiion.ignis.luaTableOf
import ru.pyxiion.ignis.unwrap
import ru.pyxiion.ignis.storage.StorageManager
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.toVec3d
import ru.pyxiion.ignis.unwrapOrNull
import java.nio.file.Path
import java.util.HashSet
import java.util.UUID

class LuaMcApi(
    private val server: MinecraftServer,
    private val storage: StorageManager,
    private val stateProvider: () -> LuaState,
    private val eventBus: EventBus,
) {
    val scheduler = Scheduler(stateProvider)
    private val playerCache = mutableMapOf<UUID, LuaValue>()

    fun invalidatePlayer(uuid: UUID) {
        playerCache.remove(uuid)
    }

    fun clearPlayerCache() {
        playerCache.clear()
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

    private fun luaGetWorld(args: Varargs): Varargs {
        val name = args.arg(1).checkjstring()
        val key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(name))
        val world = server.getWorld(key)
            ?: throw IllegalArgumentException("World '$name' not found")
        return WorldWrap.wrap(world, playerCache, { scheduler.currentTick })
    }

    private fun luaLoadStructure(args: Varargs): Varargs {
        val id = args.arg(1).checkjstring()
        val manager = server.structureTemplateManager
        val template = manager.getTemplate(Identifier.of(id))
            .orElseThrow { LuaError("Структура '$id' не найдена") }
        return StructureWrap.wrap(template)
    }

    private fun luaLoadStructureFile(args: Varargs): Varargs {
        val path = args.arg(1).checkjstring()
        val nbt = NbtIo.readCompressed(Path.of(path), NbtSizeTracker.ofUnlimitedBytes())
        val template = server.structureTemplateManager.createTemplate(nbt)
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
        for (world in server.worlds) {
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

    private fun buildSourceFromOpts(opts: LuaTable): ServerCommandSource {
        var source = server.commandSource
        opts.get("as").let { asVal ->
            if (asVal.istable()) {
                val entity = asVal.unwrapOrNull<Entity>()
                if (entity != null) {
                    source = source.withEntity(entity).withPosition(entity.entityPos)
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
            server.commandSource
        }

        return try {
            val result = server.commandManager.dispatcher.execute(cmd, source)
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
        mcMeta.rawset("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).checkjstring()
                if (key == "onlineCount") {
                    return LuaValue.valueOf(server.playerManager.currentPlayerCount.toDouble())
                }
                return LuaValue.NIL
            }
        })

        MetaTableRegistry.ITEM.set("serialise", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1).checktable()
                val stack = ItemStackWrap.unwrap(self)
                    ?: throw LuaError("item:serialise(): не ItemStack")
                val json = ItemStackCodec.encode(stack, server.registryManager)
                return LuaValue.valueOf(json)
            }
        })

        MetaTableRegistry.INVENTORY.set("serialise", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val self = args.arg(1).checktable()
                val inv = self.unwrap<SimpleInventory>()
                val json = InvWrap.serialise(inv, server.registryManager)
                return LuaValue.valueOf(json)
            }
        })

        val table = luaTableOf(
            "broadcast" to this::broadcast.asVarArgFunction(),
            "data" to storage.getGlobalData(),
            "time" to this::luaTime.asVarArgFunction(),
            "schedule" to this::luaSchedule.asVarArgFunction(),
            "scheduleRepeating" to this::luaScheduleRepeating.asVarArgFunction(),
            "cancelTask" to this::luaCancelTask.asVarArgFunction(),
            "world" to this::luaGetWorld.asVarArgFunction(),
            "players" to PlayerListWrapper(
                source = { server.playerManager.playerList },
                playerCache = playerCache,
                tickProvider = { scheduler.currentTick },
            ).toLuaValue(),
            "getEntity" to this::luaGetEntity.asVarArgFunction(),
            "getRegion" to this::luaGetRegion.asVarArgFunction(),
            "holograms" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    return HologramManager.all().map { it.toLuaValue() }.toLuaArray()
                }
            },
            "getHologram" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val uuid = UUID.fromString(args.arg(1).checkjstring())
                    return HologramManager.get(uuid)?.toLuaValue() ?: LuaValue.NIL
                }
            },
            // onlineCount handled via __index on the metatable below
            "loadStructure" to this::luaLoadStructure.asVarArgFunction(),
            "loadStructureFile" to this::luaLoadStructureFile.asVarArgFunction(),
            "dump" to this::luaDump.asVarArgFunction(),
            "getMetatable" to this::luaGetMetatable.asVarArgFunction(),
            "serialise" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val type = args.arg(1).checkjstring()
                    when (type) {
                        "item" -> {
                            val obj = args.arg(2)
                            val stack = ItemStackWrap.unwrap(obj)
                                ?: throw LuaError("mc.serialise('item', ...): ожидается ItemStack")
                            val json = ItemStackCodec.encode(stack, server.registryManager)
                            return LuaValue.valueOf(json)
                        }
                        "inventory" -> {
                            val obj = args.arg(2)
                            val inv = obj.unwrap<SimpleInventory>()
                            val json = InvWrap.serialise(inv, server.registryManager)
                            return LuaValue.valueOf(json)
                        }
                        else -> throw LuaError("mc.serialise: неизвестный тип '$type'")
                    }
                }
            },
            "deserialise" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val type = args.arg(1).checkjstring()
                    val data = args.arg(2).checkjstring()
                    when (type) {
                        "item" -> {
                            val stack = ItemStackCodec.decode(data, server.registryManager)
                            return if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
                        }
                        "inventory" -> {
                            val inv = InvWrap.deserialise(data, server.registryManager)
                            return InvWrap.wrap(inv)
                        }
                        else -> throw LuaError("mc.deserialise: неизвестный тип '$type'")
                    }
                }
            },
            "createInventory" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val size = args.arg(1).checkint()
                    if (size < 9 || size > 54 || size % 9 != 0)
                        throw LuaError("mc.createInventory: размер должен быть кратен 9 и от 9 до 54")
                    return InvWrap.wrap(LockableInventory(size))
                }
            },

            "runtimeNamespace" to LuaValue.valueOf(
                FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace
            ),

            "mapped" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val mappingResolver = FabricLoader.getInstance().mappingResolver
                    val mappingNamespace = mappingResolver.currentRuntimeNamespace

                    return valueOf(mappingResolver.mapClassName("named", args.arg(1).checkjstring()))
                }
            },

            "registerBehaviour" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    require(args.narg() == 2) { "registerBehaviour(id, fn) requires 2 arguments" }
                    val id = args.arg(1).checkjstring()
                    val fn = args.arg(2).checkfunction()
                    MobAIManager.registerBehaviour(id, fn)
                    return LuaValue.NIL
                }
            },
            "createBossBar" to object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val title = args.arg(1).checkjstring()
                    val color = args.arg(2).optjstring("white")
                    val style = args.arg(3).optjstring("progress")
                    val wrapper = BossBarWrapper(title, color, style)
                    BossBarManager.register(wrapper)
                    return wrapper.toLuaValue()
                }
            },
            "execute" to this::luaExecute.asVarArgFunction(),
        )

        val onHandler: (Varargs) -> Varargs = { args: Varargs ->
            require(args.narg() == 2) { "on(event, handler) requires 2 arguments" }
            val eventName = args.checkjstring(1)
            val handler = args.checkfunction(2)
            val id = eventBus.on(eventName, handler)
            LuaValue.valueOf(id)
        }
        table.set("on", onHandler.asVarArgFunction())

        val offHandler: (Varargs) -> Varargs = { args: Varargs ->
            require(args.narg() == 1) { "off(id) requires 1 argument" }
            val id = args.checkint(1)
            LuaValue.valueOf(eventBus.off(id))
        }
        table.set("off", offHandler.asVarArgFunction())

        val emitHandler: (Varargs) -> Varargs = { args ->
            require(args.narg() >= 1) { "emit(event, ...) requires at least 1 argument" }
            val eventName = args.checkjstring(1)
            val eventArgs = if (args.narg() >= 2) {
                (2..args.narg()).map { args.arg(it) }.toTypedArray()
            } else emptyArray<LuaValue>()
            eventBus.fire(eventName, *eventArgs)
            LuaValue.NIL
        }
        table.set("emit", emitHandler.asVarArgFunction())

        table.set("createItem", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val stack = ItemBuilder.fromLua(args)
                return ItemStackWrap.wrap(stack)
            }
        })

        AsyncLib(server, stateProvider(), scheduler).install(table)

        table.setmetatable(mcMeta)
        return table
    }
}