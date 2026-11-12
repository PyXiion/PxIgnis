package ru.pyxiion.ignis.api.wrapper

import ru.pyxiion.ignis.wrapObject
import ru.pyxiion.ignis.Compat
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.GameType
import net.minecraft.world.level.portal.TeleportTransition
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import me.lucko.fabric.api.permissions.v0.Permissions
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.lookup
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.toLuaArray
import ru.pyxiion.ignis.isAdmin
import ru.pyxiion.ignis.unwrap

object PlayerWrap {

    fun wrapAll(players: List<ServerPlayer>): LuaValue =
        players.filterNot { it.isRemoved }.map { wrap(it) }.toLuaArray()

    fun wrap(entity: ServerPlayer): LuaValue {
        val t = wrapObject(MetaTableRegistry.PLAYER, "player", entity)
        t.rawset("data", PxIgnis.runtimeOrNull?.storage?.getPlayerData(entity.uuid.toString())
            ?: throw IllegalStateException("PxIgnis not initialized"))
        return t
    }

    private val BUILT by lazy { metaTable<ServerPlayer> {
        inherit(EntityWrap.BUILT)

        prop(
            "food",
            get = { LuaValue.valueOf(foodData.foodLevel) },
            set = { v -> foodData.foodLevel = v.toint() }
        )
        prop("saturation") { LuaValue.valueOf(foodData.saturationLevel.toDouble()) }
        prop(
            "gamemode",
            get = { LuaValue.valueOf(gameMode().getName()) },
            set = { v ->
                val name = v.checkjstring()
                val mode = GameType.entries.firstOrNull { it.getName() == name }
                    ?: throw LuaError("Неизвестный режим игры '$name' (допустимо: ${GameType.entries.joinToString { it.getName() }})")
                setGameMode(mode)
            }
        )
        prop("ping") { LuaValue.valueOf(connection.latency()) }
        prop("xpLevel") { LuaValue.valueOf(experienceLevel) }
        prop("xpProgress") { LuaValue.valueOf(experienceProgress.toDouble()) }
        prop("isOp") { LuaValue.valueOf(isAdmin()) }
        prop("selectedSlot") { LuaValue.valueOf(inventory.selectedSlot) }
        prop("isFlying") { LuaValue.valueOf(abilities.flying) }
        prop(
            "sidebar",
            get = {
                SidebarManager.get(this)?.toLuaValue() ?: LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    SidebarManager.removeForPlayer(this)
                } else if (v.istable()) {
                    val config = v.checktable()
                    val existing = SidebarManager.get(this)
                    val wrapper = existing ?: SidebarManager.create(
                        this,
                        config.rawget("title").optjstring("Sidebar")
                    )

                    val title = config.rawget("title")
                    if (title.isstring()) wrapper.setTitle(title.tojstring())

                    val lines = config.rawget("lines")
                    if (lines.istable()) wrapper.setLinesFromTable(lines.checktable())

                    val visible = config.rawget("visible")
                    if (visible.isboolean()) {
                        if (visible.toboolean()) wrapper.show() else wrapper.hide()
                    } else if (existing == null) {
                        wrapper.show()
                    }
                }
            }
        )
        propWithTable("data") { self -> self.rawget("data") }

        method("hasPermission") { args ->
            val self = args.arg(1).checktable()
            LuaValue.valueOf(Permissions.check(self.unwrap<ServerPlayer>(), args.arg(2).checkjstring()))
        }

        method("sendMessage") { args ->
            args.arg(1).checktable().unwrap<ServerPlayer>()
                .sendSystemMessage(Component.literal(args.arg(2).checkjstring()))
            LuaValue.NIL
        }

        method("sendActionBar") { args ->
            val self = args.arg(1).checktable()
            self.unwrap<ServerPlayer>().connection.send(
                ClientboundSetActionBarTextPacket(Component.literal(args.arg(2).checkjstring()))
            )
            LuaValue.NIL
        }

        method("sendTitle") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val arg2 = args.arg(2)

            // sendTitle(title, subtitle?) and sendTitle { title, subtitle?, fadeIn?, stay?, fadeOut? } share one path
            val opts = if (arg2.istable()) arg2.checktable() else null
            val title = opts?.get("title")?.optjstring("") ?: arg2.checkjstring()
            val subtitle = (opts?.get("subtitle") ?: args.arg(3)).optjstring(null)?.takeIf { it.isNotEmpty() }
            val fadeIn = opts?.get("fadeIn")?.optint(20) ?: 20
            val stay = opts?.get("stay")?.optint(60) ?: 60
            val fadeOut = opts?.get("fadeOut")?.optint(20) ?: 20

            e.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
            if (subtitle != null) e.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
            e.connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
            LuaValue.NIL
        }

        method("kick") { args ->
            val self = args.arg(1).checktable()
            self.unwrap<ServerPlayer>().connection
                .disconnect(Component.literal(args.arg(2).optjstring("Kicked")))
            LuaValue.NIL
        }

        method("teleport") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val x = args.arg(2).checkdouble()
            val y = args.arg(3).checkdouble()
            val z = args.arg(4).checkdouble()
            val worldName = args.arg(5).optjstring(null)
            val srv = e.level().server
                ?: throw IllegalStateException("Server not available")
            val targetWorld = if (worldName != null) {
                val key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(worldName))
                srv.getLevel(key)
            } else null

            if (worldName != null && targetWorld == null) {
                throw IllegalArgumentException("World '$worldName' not found")
            }

            if (targetWorld != null && targetWorld != e.level()) {
                e.teleport(
                    TeleportTransition(
                        targetWorld, Vec3(x, y, z), Vec3.ZERO,
                        e.yRot, e.xRot, TeleportTransition.DO_NOTHING
                    )
                )
            } else {
                e.teleportTo(x, y, z)
            }
            LuaValue.NIL
        }

        method("heal") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            e.heal(args.arg(2).checkdouble().toFloat())
            LuaValue.NIL
        }

        method("playSound") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val soundId = args.arg(2).checkjstring()
            val volume = args.arg(3).optdouble(1.0).toFloat()
            val pitch = args.arg(4).optdouble(1.0).toFloat()
            val soundEntry = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(soundId))
                .orElseThrow { IllegalArgumentException("Sound $soundId not found") }
            val pos = e.position()
            e.connection.send(
                ClientboundSoundPacket(
                    soundEntry, SoundSource.PLAYERS,
                    pos.x, pos.y, pos.z,
                    volume, pitch, e.level().random.nextLong()
                )
            )
            LuaValue.NIL
        }

        method("give") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val first = args.arg(2)
            val stack: ItemStack = when {
                first.isstring() -> {
                    val id = first.tojstring()
                    val count = args.arg(3).optint(1)
                    val item = BuiltInRegistries.ITEM.lookup("Предмет", id)
                    ItemStack(item, count)
                }

                first.istable() -> {
                    ItemStackWrap.unwrap(first)
                        ?: throw LuaError("give: ожидается ItemStack от mc.createItem или ID предмета")
                }

                else -> throw LuaError("give: ожидается строка (ID предмета) или ItemStack от mc.createItem")
            }
            Compat.giveItem(e, stack)
            LuaValue.NIL
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val slot = args.arg(2).checkint()
            val itemVal = args.arg(3)

            val inv = e.inventory
            if (slot < 0 || slot >= inv.containerSize) {
                throw LuaError("setItem: слот $slot вне диапазона (0-${inv.containerSize - 1})")
            }

            val stack = if (itemVal.isnil()) {
                ItemStack.EMPTY
            } else {
                ItemStackWrap.unwrap(itemVal)
                    ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
            }

            inv.setItem(slot, stack)
            e.containerMenu.broadcastChanges()
            LuaValue.NIL
        }

        method("getItem") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val slot = args.arg(2).checkint()
            val inv = e.inventory
            if (slot < 0 || slot >= inv.containerSize) return@method LuaValue.NIL
            val stack = inv.getItem(slot)
            if (stack.isEmpty) LuaValue.NIL else ItemStackWrap.wrap(stack)
        }

        method("clear") { args ->
            args.arg(1).checktable().unwrap<ServerPlayer>().inventory.clearContent()
            LuaValue.NIL
        }
    }}

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }
}
