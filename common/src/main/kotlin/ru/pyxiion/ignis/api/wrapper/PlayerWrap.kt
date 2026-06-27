package ru.pyxiion.ignis.api.wrapper

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
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.GameType
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.IgnisPlatform
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.api.MetaTableRegistry
import ru.pyxiion.ignis.api.manager.SidebarManager
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrap

object PlayerWrap {

    fun wrap(entity: ServerPlayer): LuaValue {
        val t = EntityWrap.wrap(entity)
        t.setmetatable(MetaTableRegistry.PLAYER)
        t.rawset("__pxrp_type", LuaValue.valueOf("player"))
        t.rawset("data", PxIgnis.storageManager?.getPlayerData(entity.uuid.toString())
            ?: throw IllegalStateException("PxIgnis not initialized"))
        return t
    }

    private val BUILT by lazy { metaTable<ServerPlayer> {
        inherit { MetaTableRegistry.ENTITY }

        prop(
            "food",
            get = { LuaValue.valueOf(foodData.foodLevel) },
            set = { v -> foodData.foodLevel = v.toint() }
        )
        prop("saturation") { LuaValue.valueOf(foodData.saturationLevel.toDouble()) }
        prop(
            "gamemode",
            get = { LuaValue.valueOf(gameMode.getGameModeForPlayer().getId()) },
            set = { v -> GameType.byId(v.toint())?.let { setGameMode(it) } }
        )
        prop("ping") { LuaValue.valueOf(connection.latency()) }
        prop("xpLevel") { LuaValue.valueOf(experienceLevel) }
        prop("xpProgress") { LuaValue.valueOf(experienceProgress.toDouble()) }
        prop("isOp") { LuaValue.valueOf(Compat.isAdmin(this)) }
        prop("selected") { LuaValue.valueOf(inventory.selectedSlot) }
        prop("isFlying") { LuaValue.valueOf(getAbilities().flying) }
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

        prop(
            "head",
            get = {
                val stack = getItemBySlot(EquipmentSlot.HEAD)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.HEAD, v) }
        )
        prop(
            "chest",
            get = {
                val stack = getItemBySlot(EquipmentSlot.CHEST)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.CHEST, v) }
        )
        prop(
            "legs",
            get = {
                val stack = getItemBySlot(EquipmentSlot.LEGS)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.LEGS, v) }
        )
        prop(
            "feet",
            get = {
                val stack = getItemBySlot(EquipmentSlot.FEET)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.FEET, v) }
        )
        prop(
            "mainhand",
            get = {
                val stack = getItemBySlot(EquipmentSlot.MAINHAND)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.MAINHAND, v) }
        )
        prop(
            "offhand",
            get = {
                val stack = getItemBySlot(EquipmentSlot.OFFHAND)
                if (!stack.isEmpty) ItemStackWrap.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.OFFHAND, v) }
        )

        method("hasPermission") { args ->
            val self = args.arg(1).checktable()
            LuaValue.valueOf(IgnisPlatform.instance.checkPermission(self.unwrap<ServerPlayer>().commandSource(), args.arg(2).checkjstring()))
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

            if (arg2.istable()) {
                val t = arg2.checktable()
                val title = t.get("title").optjstring("")
                val subtitle = t.get("subtitle").optjstring("")
                val fadeIn = t.get("fadeIn").optint(20)
                val stay = t.get("stay").optint(60)
                val fadeOut = t.get("fadeOut").optint(20)
                e.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
                if (subtitle.isNotEmpty()) {
                    e.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
                }
                e.connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
            } else {
                val title = arg2.checkjstring()
                val subtitle = args.arg(3).optjstring(null)
                e.connection.send(ClientboundSetTitlesAnimationPacket(20, 60, 20))
                if (subtitle != null) {
                    e.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
                }
                e.connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
            }
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
            val srv = (e.level() as ServerLevel).server
                ?: throw IllegalStateException("Server not available")
            val targetWorld = if (worldName != null) {
                val key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(worldName))
                srv.getLevel(key)
            } else null

            if (worldName != null && targetWorld == null) {
                throw IllegalArgumentException("Level '$worldName' not found")
            }

            if (targetWorld != null && targetWorld != e.level()) {
                e.teleportTo(
                    targetWorld, x, y, z, emptySet(),
                    e.yRot, e.xRot, false
                )
            } else {
                e.teleportTo(x, y, z)
            }
            LuaValue.NIL
        }

        method("damage") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val amount = args.arg(2).checkdouble().toFloat()
            e.hurtServer(e.level() as ServerLevel, e.damageSources().generic(), amount)
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
                    val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id))
                        ?: throw LuaError("Предмет '$id' не найден")
                    ItemStack(item, count)
                }

                first.istable() -> {
                    ItemStackWrap.unwrap(first)
                        ?: throw LuaError("give: ожидается ItemStack от mc.createItem или ID предмета")
                }

                else -> throw LuaError("give: ожидается строка (ID предмета) или ItemStack от mc.createItem")
            }
            e.inventory.placeItemBackInInventory(stack)
            LuaValue.NIL
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayer>()
            val slot = args.arg(2).checkint()
            val itemVal = args.arg(3)

            val inv = e.inventory
            if (slot < 0 || slot >= inv.getContainerSize()) {
                throw LuaError("setItem: слот $slot вне диапазона (0-${inv.getContainerSize() - 1})")
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
            if (slot < 0 || slot >= inv.getContainerSize()) return@method LuaValue.NIL
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

    private fun setSlot(e: ServerPlayer, slot: EquipmentSlot, value: LuaValue) {
        val stack = if (value.isnil()) {
            ItemStack.EMPTY
        } else {
            ItemStackWrap.unwrap(value)
                ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
        }
        e.setItemSlot(slot, stack)
        e.containerMenu.broadcastChanges()
    }
}
