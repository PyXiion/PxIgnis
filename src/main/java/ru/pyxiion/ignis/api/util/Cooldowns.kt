package ru.pyxiion.ignis.api.util

import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemCooldowns

object Cooldowns {
    /** Ticks left on a cooldown group (an item id, or a `use_cooldown` group), 0 when it is not cooling down. */
    fun remaining(cooldowns: ItemCooldowns, group: Identifier): Int {
        val c = cooldowns.cooldowns[group] ?: return 0
        return maxOf(0, c.endTime() - cooldowns.tickCount)
    }
}
