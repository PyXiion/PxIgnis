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
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import me.lucko.fabric.api.permissions.v0.Permissions
import ru.pyxiion.ignis.PxIgnis

class PlayerWrapper(private val entity: ServerPlayerEntity) {

    fun toLuaValue(): LuaValue {
        val t = LuaTable()
        t.setmetatable(MetaTableRegistry.PLAYER)
        t.rawset("__pxrp_type", LuaValue.valueOf("player"))
        t.rawset("__pxrp_object", CoerceJavaToLua.coerce(entity))
        t.rawset("data", PxIgnis.storageManager?.getPlayerData(entity.uuid.toString())
            ?: throw IllegalStateException("PxIgnis not initialized"))
        return t
    }

    companion object {
        private val playerKeys = listOf(
            "food", "saturation", "gamemode", "ping", "xpLevel", "xpProgress",
            "isOp", "selectedSlot", "isFlying", "data", "sidebar",
            "hasPermission",
            "sendMessage", "sendActionBar", "sendTitle", "kick",
            "teleport", "damage", "heal", "playSound", "give", "setItem", "getItem", "clear",
        )

        fun initMeta(meta: LuaTable) {
            meta.set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity

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
                        "sidebar" -> {
                            SidebarManager.get(e)?.toLuaValue() ?: LuaValue.NIL
                        }
                        else -> {
                            val metaVal = meta.get(key)
                            if (!metaVal.isnil()) return metaVal

                            val entityIndex = MetaTableRegistry.ENTITY.get("__index")
                            if (entityIndex.isfunction()) {
                                return entityIndex.invoke(LuaValue.varargsOf(arrayOf(self, LuaValue.valueOf(key))))
                            }
                            LuaValue.NIL
                        }
                    }
                }
            })

            meta.set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val key = args.arg(2).tojstring()
                    val value = args.arg(3)
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity

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
                        "sidebar" -> {
                            if (value.isnil()) {
                                SidebarManager.removeForPlayer(e)
                            } else if (value.istable()) {
                                val config = value.checktable()
                                val existing = SidebarManager.get(e)
                                val wrapper = existing ?: SidebarManager.create(e, config.rawget("title").optjstring("Sidebar"))

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
                        else -> {
                            val entityNewIndex = MetaTableRegistry.ENTITY.get("__newindex")
                            if (entityNewIndex.isfunction()) {
                                entityNewIndex.invoke(LuaValue.varargsOf(arrayOf(self, LuaValue.valueOf(key), value)))
                            }
                        }
                    }
                    return LuaValue.NIL
                }
            })

            meta.set("__pairs", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1)
                    val entityKeys = EntityWrapper.entityKeys
                    val allKeys = playerKeys + entityKeys
                    val iterator = object : VarArgFunction() {
                        private var index = 0
                        override fun invoke(args: Varargs): Varargs {
                            if (index >= allKeys.size) return LuaValue.NIL
                            val key = allKeys[index]
                            index++
                            val value = self.get(key)
                            return LuaValue.varargsOf(arrayOf(LuaValue.valueOf(key), value))
                        }
                    }
                    return LuaValue.varargsOf(arrayOf(iterator, self, LuaValue.NIL))
                }
            })

            meta.rawset("hasPermission", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val permission = args.arg(2).checkjstring()
                    return LuaValue.valueOf(Permissions.check(e, permission))
                }
            })

            meta.rawset("sendMessage", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    e.sendMessage(Text.literal(args.arg(2).checkjstring()))
                    return LuaValue.NIL
                }
            })

            meta.rawset("sendActionBar", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    e.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal(args.arg(2).checkjstring())))
                    return LuaValue.NIL
                }
            })

            meta.rawset("sendTitle", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val title = args.arg(2).checkjstring()
                    val subtitle = args.arg(3).optjstring(null)
                    e.networkHandler.sendPacket(TitleFadeS2CPacket(20, 60, 20))
                    if (subtitle != null) {
                        e.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal(subtitle)))
                    }
                    e.networkHandler.sendPacket(TitleS2CPacket(Text.literal(title)))
                    return LuaValue.NIL
                }
            })

            meta.rawset("kick", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val reason = args.arg(2).optjstring("Kicked")
                    e.networkHandler.disconnect(Text.literal(reason))
                    return LuaValue.NIL
                }
            })

            meta.rawset("teleport", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
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
                    return LuaValue.NIL
                }
            })

            meta.rawset("damage", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val amount = args.arg(2).checkdouble().toFloat()
                    e.damage(e.entityWorld, e.entityWorld.damageSources.generic(), amount)
                    return LuaValue.NIL
                }
            })

            meta.rawset("heal", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val amount = args.arg(2).checkdouble().toFloat()
                    e.heal(amount)
                    return LuaValue.NIL
                }
            })

            meta.rawset("playSound", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
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
            })

            meta.rawset("give", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
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
            })

            meta.rawset("setItem", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
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
            })

            meta.rawset("getItem", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    val slot = args.arg(2).checkint()
                    val inv = e.inventory
                    if (slot < 0 || slot >= inv.size()) {
                        return LuaValue.NIL
                    }
                    val stack = inv.getStack(slot)
                    if (stack.isEmpty) return LuaValue.NIL
                    return ItemStackWrapper.wrap(stack)
                }
            })

            meta.rawset("clear", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val self = args.arg(1).checktable()
                    val e = self.rawget("__pxrp_object").checkuserdata() as ServerPlayerEntity
                    e.inventory.clear()
                    return LuaValue.NIL
                }
            })
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
}
