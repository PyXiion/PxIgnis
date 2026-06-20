package ru.pyxiion.ignis.api

import net.minecraft.command.DefaultPermissions
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.world.GameMode
import net.minecraft.world.TeleportTarget
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import me.lucko.fabric.api.permissions.v0.Permissions
import ru.pyxiion.ignis.PxIgnis
import ru.pyxiion.ignis.unwrap

object PlayerWrap {

    fun wrap(entity: ServerPlayerEntity): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.PLAYER)
        t.rawset("__pxrp_type", LuaValue.valueOf("player"))
        t.rawset("__pxrp_object", LuaValue.userdataOf(entity))
        t.rawset("data", PxIgnis.storageManager?.getPlayerData(entity.uuid.toString())
            ?: throw IllegalStateException("PxIgnis not initialized"))
        return t
    }

    private val BUILT = metaTable<ServerPlayerEntity> {
        inherit { MetaTableRegistry.ENTITY }
        pairsKeys(EntityWrap.entityKeys)

        prop("food",
            get = { LuaValue.valueOf(hungerManager.foodLevel) },
            set = { v -> hungerManager.foodLevel = v.toint() }
        )
        prop("saturation") { LuaValue.valueOf(hungerManager.saturationLevel.toDouble()) }
        prop("gamemode",
            get = { LuaValue.valueOf(interactionManager.gameMode.id) },
            set = { v -> GameMode.byId(v.tojstring())?.let { changeGameMode(it) } }
        )
        prop("ping") { LuaValue.valueOf(networkHandler.latency) }
        prop("xpLevel") { LuaValue.valueOf(experienceLevel) }
        prop("xpProgress") { LuaValue.valueOf(experienceProgress.toDouble()) }
        prop("isOp") { LuaValue.valueOf(getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS)) }
        prop("selectedSlot") { LuaValue.valueOf(inventory.selectedSlot) }
        prop("isFlying") { LuaValue.valueOf(abilities.flying) }
        prop("sidebar",
            get = {
                SidebarManager.get(this)?.toLuaValue() ?: LuaValue.NIL
            },
            set = { v ->
                if (v.isnil()) {
                    SidebarManager.removeForPlayer(this)
                } else if (v.istable()) {
                    val config = v.checktable()
                    val existing = SidebarManager.get(this)
                    val wrapper = existing ?: SidebarManager.create(this, config.rawget("title").optjstring("Sidebar"))

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
        prop("data") { _, self -> self.rawget("data") }

        prop("head",
            get = {
                val stack = getEquippedStack(EquipmentSlot.HEAD)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.HEAD.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), v) }
        )
        prop("chest",
            get = {
                val stack = getEquippedStack(EquipmentSlot.CHEST)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.CHEST.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), v) }
        )
        prop("legs",
            get = {
                val stack = getEquippedStack(EquipmentSlot.LEGS)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.LEGS.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), v) }
        )
        prop("feet",
            get = {
                val stack = getEquippedStack(EquipmentSlot.FEET)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, EquipmentSlot.FEET.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), v) }
        )
        prop("mainhand",
            get = {
                val stack = getEquippedStack(EquipmentSlot.MAINHAND)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, inventory.selectedSlot, v) }
        )
        prop("offhand",
            get = {
                val stack = getEquippedStack(EquipmentSlot.OFFHAND)
                if (!stack.isEmpty) ItemStackWrapper.wrap(stack) else LuaValue.NIL
            },
            set = { v -> setSlot(this, PlayerInventory.OFF_HAND_SLOT, v) }
        )

        method("hasPermission") { args ->
            val self = args.arg(1).checktable()
            LuaValue.valueOf(Permissions.check(self.unwrap<ServerPlayerEntity>(), args.arg(2).checkjstring()))
        }

        method("sendMessage") { args ->
            args.arg(1).checktable().unwrap<ServerPlayerEntity>()
                .sendMessage(Text.literal(args.arg(2).checkjstring()))
            LuaValue.NIL
        }

        method("sendActionBar") { args ->
            val self = args.arg(1).checktable()
            self.unwrap<ServerPlayerEntity>().networkHandler.sendPacket(
                OverlayMessageS2CPacket(Text.literal(args.arg(2).checkjstring()))
            )
            LuaValue.NIL
        }

        method("sendTitle") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val title = args.arg(2).checkjstring()
            val subtitle = args.arg(3).optjstring(null)
            e.networkHandler.sendPacket(TitleFadeS2CPacket(20, 60, 20))
            if (subtitle != null) {
                e.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal(subtitle)))
            }
            e.networkHandler.sendPacket(TitleS2CPacket(Text.literal(title)))
            LuaValue.NIL
        }

        method("kick") { args ->
            val self = args.arg(1).checktable()
            self.unwrap<ServerPlayerEntity>().networkHandler
                .disconnect(Text.literal(args.arg(2).optjstring("Kicked")))
            LuaValue.NIL
        }

        method("teleport") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val x = args.arg(2).checkdouble()
            val y = args.arg(3).checkdouble()
            val z = args.arg(4).checkdouble()
            val worldName = args.arg(5).optjstring(null)
            val srv = (e.entityWorld as ServerWorld).server
                ?: throw IllegalStateException("Server not available")
            val targetWorld = if (worldName != null) {
                val key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldName))
                srv.getWorld(key)
            } else null

            if (worldName != null && targetWorld == null) {
                throw IllegalArgumentException("World '$worldName' not found")
            }

            if (targetWorld != null && targetWorld != e.entityWorld) {
                e.teleportTo(TeleportTarget(
                    targetWorld, Vec3d(x, y, z), Vec3d.ZERO,
                    e.yaw, e.pitch, TeleportTarget.NO_OP
                ))
            } else {
                e.requestTeleport(x, y, z)
            }
            LuaValue.NIL
        }

        method("damage") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val amount = args.arg(2).checkdouble().toFloat()
            e.damage(e.entityWorld, e.entityWorld.damageSources.generic(), amount)
            LuaValue.NIL
        }

        method("heal") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            e.heal(args.arg(2).checkdouble().toFloat())
            LuaValue.NIL
        }

        method("playSound") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val soundId = args.arg(2).checkjstring()
            val volume = args.arg(3).optdouble(1.0).toFloat()
            val pitch = args.arg(4).optdouble(1.0).toFloat()
            val soundEntry = Registries.SOUND_EVENT.getEntry(Identifier.of(soundId))
                .orElseThrow { IllegalArgumentException("Sound $soundId not found") }
            val pos = e.entityPos
            e.networkHandler.sendPacket(
                PlaySoundS2CPacket(
                    soundEntry, SoundCategory.PLAYERS,
                    pos.x, pos.y, pos.z,
                    volume, pitch, e.entityWorld.random.nextLong()
                )
            )
            LuaValue.NIL
        }

        method("give") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val first = args.arg(2)
            val stack: ItemStack = when {
                first.isstring() -> {
                    val id = first.tojstring()
                    val count = args.arg(3).optint(1)
                    val item = Registries.ITEM.get(Identifier.of(id))
                        ?: throw LuaError("Предмет '$id' не найден")
                    ItemStack(item, count)
                }
                first.istable() -> {
                    ItemStackWrapper.unwrap(first)
                        ?: throw LuaError("give: ожидается ItemStack от mc.createItem или ID предмета")
                }
                else -> throw LuaError("give: ожидается строка (ID предмета) или ItemStack от mc.createItem")
            }
            e.inventory.offerOrDrop(stack)
            LuaValue.NIL
        }

        method("setItem") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val slot = args.arg(2).checkint()
            val itemVal = args.arg(3)

            val inv = e.inventory
            if (slot < 0 || slot >= inv.size()) {
                throw LuaError("setItem: слот $slot вне диапазона (0-${inv.size() - 1})")
            }

            val stack = if (itemVal.isnil()) {
                ItemStack.EMPTY
            } else {
                ItemStackWrapper.unwrap(itemVal)
                    ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
            }

            inv.setStack(slot, stack)
            e.currentScreenHandler.sendContentUpdates()
            LuaValue.NIL
        }

        method("getItem") { args ->
            val self = args.arg(1).checktable()
            val e = self.unwrap<ServerPlayerEntity>()
            val slot = args.arg(2).checkint()
            val inv = e.inventory
            if (slot < 0 || slot >= inv.size()) return@method LuaValue.NIL
            val stack = inv.getStack(slot)
            if (stack.isEmpty) LuaValue.NIL else ItemStackWrapper.wrap(stack)
        }

        method("clear") { args ->
            args.arg(1).checktable().unwrap<ServerPlayerEntity>().inventory.clear()
            LuaValue.NIL
        }
    }

    fun initMeta(meta: LuaTable) {
        BUILT.apply(meta)
    }

    private fun setSlot(e: ServerPlayerEntity, slot: Int, value: LuaValue) {
        val stack = if (value.isnil()) {
            ItemStack.EMPTY
        } else {
            ItemStackWrapper.unwrap(value)
                ?: throw LuaError("setItem: ожидается ItemStack от mc.createItem")
        }
        e.inventory.setStack(slot, stack)
        e.currentScreenHandler.sendContentUpdates()
    }
}
