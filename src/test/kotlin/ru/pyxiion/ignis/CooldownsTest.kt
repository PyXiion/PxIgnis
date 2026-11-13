package ru.pyxiion.ignis

import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemCooldowns
import ru.pyxiion.ignis.api.util.Cooldowns

class CooldownsTest {
    @Test
    fun `remaining ticks count down and end`() {
        val cd = ItemCooldowns()
        val id = Identifier.parse("minecraft:snowball")
        assertEquals(0, Cooldowns.remaining(cd, id))
        cd.addCooldown(id, 5)
        assertEquals(5, Cooldowns.remaining(cd, id))
        repeat(2) { cd.tick() }
        assertEquals(3, Cooldowns.remaining(cd, id))
        cd.removeCooldown(id)
        assertEquals(0, Cooldowns.remaining(cd, id))
    }
}
