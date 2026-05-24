package ru.pyxiion.pxrp.api

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
import org.luaj.vm2.lib.VarArgFunction
import ru.pyxiion.pxrp.PxRp

class Player(private val entity: ServerPlayerEntity) {

    private val server get() = (entity.entityWorld as ServerWorld).server
        ?: throw IllegalStateException("Server not available")

    fun toLuaValue(): LuaValue {
        val e = entity
        val srv = server
        val entityValue = EntityWrapper(e).toLuaValue()

        val metatable = LuaTable()
        metatable.set("__index", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                return when (key) {
                    "food" -> LuaValue.valueOf(e.hungerManager.foodLevel)
                    "saturation" -> LuaValue.valueOf(e.hungerManager.saturationLevel.toDouble())
                    "gamemode" -> LuaValue.valueOf(e.interactionManager.gameMode.id)
                    "ping" -> LuaValue.valueOf(e.networkHandler.latency)
                    "xpLevel" -> LuaValue.valueOf(e.experienceLevel)
                    "xpProgress" -> LuaValue.valueOf(e.experienceProgress.toDouble())
                    "isOp" -> LuaValue.valueOf(e.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS))
                    "selectedSlot" -> LuaValue.valueOf(e.inventory.selectedSlot)
                    "isFlying" -> LuaValue.valueOf(e.abilities.flying)
                    else -> entityValue.get(key)
                }
            }
        })

        metatable.set("__newindex", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val key = args.arg(2).tojstring()
                val value = args.arg(3)
                when (key) {
                    "food" -> e.hungerManager.foodLevel = value.toint()
                    "gamemode" -> {
                        GameMode.byId(value.tojstring())?.let { e.changeGameMode(it) }
                    }
                    "head" -> setSlot(e, EquipmentSlot.HEAD.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), value)
                    "chest" -> setSlot(e, EquipmentSlot.CHEST.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), value)
                    "legs" -> setSlot(e, EquipmentSlot.LEGS.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), value)
                    "feet" -> setSlot(e, EquipmentSlot.FEET.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE), value)
                    "mainhand" -> setSlot(e, e.inventory.selectedSlot, value)
                    "offhand" -> setSlot(e, PlayerInventory.OFF_HAND_SLOT, value)
                    else -> entityValue.set(key, value)
                }
                return LuaValue.NIL
            }
        })

        val t = LuaTable()
        t.setmetatable(metatable)

        t.rawset("data", PxRp.storageManager?.getPlayerData(e.uuid.toString())
            ?: throw IllegalStateException("PxRP not initialized"))

        t.rawset("sendMessage", sendMessage(e))
        t.rawset("sendActionBar", sendActionBar(e))
        t.rawset("sendTitle", sendTitle(e))
        t.rawset("kick", kick(e))
        t.rawset("teleport", teleport(e, srv))
        t.rawset("damage", damage(e))
        t.rawset("heal", heal(e))
        t.rawset("playSound", playSound(e))
        t.rawset("give", give(e))
        t.rawset("setItem", setItem(e))
        t.rawset("getItem", getItem(e))
        t.rawset("clear", clear(e))

        return t
    }

    companion object {
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

        private fun sendMessage(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                e.sendMessage(Text.literal(args.arg(2).checkjstring()))
                return LuaValue.NIL
            }
        }

        private fun sendActionBar(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                e.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal(args.arg(2).checkjstring())))
                return LuaValue.NIL
            }
        }

        private fun sendTitle(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val title = args.arg(2).checkjstring()
                val subtitle = args.arg(3).optjstring(null)
                e.networkHandler.sendPacket(TitleFadeS2CPacket(20, 60, 20))
                if (subtitle != null) {
                    e.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal(subtitle)))
                }
                e.networkHandler.sendPacket(TitleS2CPacket(Text.literal(title)))
                return LuaValue.NIL
            }
        }

        private fun kick(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val reason = args.arg(2).optjstring("Kicked")
                e.networkHandler.disconnect(Text.literal(reason))
                return LuaValue.NIL
            }
        }

        private fun teleport(e: ServerPlayerEntity, srv: net.minecraft.server.MinecraftServer) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val x = args.arg(2).checkdouble()
                val y = args.arg(3).checkdouble()
                val z = args.arg(4).checkdouble()
                val worldName = args.arg(5).optjstring(null)
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
                return LuaValue.NIL
            }
        }

        private fun damage(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val amount = args.arg(2).checkdouble().toFloat()
                e.damage(e.entityWorld, e.entityWorld.damageSources.generic(), amount)
                return LuaValue.NIL
            }
        }

        private fun heal(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val amount = args.arg(2).checkdouble().toFloat()
                e.heal(amount)
                return LuaValue.NIL
            }
        }

        private fun playSound(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
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
                return LuaValue.NIL
            }
        }

        private fun give(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
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
                return LuaValue.NIL
            }
        }

        private fun setItem(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
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
                return LuaValue.NIL
            }
        }

        private fun getItem(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val slot = args.arg(2).checkint()
                val inv = e.inventory
                if (slot < 0 || slot >= inv.size()) {
                    return LuaValue.NIL
                }
                val stack = inv.getStack(slot)
                if (stack.isEmpty) return LuaValue.NIL
                return ItemStackWrapper.wrap(stack)
            }
        }

        private fun clear(e: ServerPlayerEntity) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                e.inventory.clear()
                return LuaValue.NIL
            }
        }
    }
}
