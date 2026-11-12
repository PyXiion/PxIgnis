package ru.pyxiion.ignis.api

import org.luaj.vm2.LuaTable
import ru.pyxiion.ignis.api.wrapper.*

/**
 * Every wrapper type has one entry here; the named getters, [get] and [init] are all built from [entries].
 * [init] gives every type a fresh metatable and lets its wrapper fill it. The fill functions are lambdas so that the
 * wrapper objects (and the Minecraft classes they reference) are only touched by [init].
 */
object MetaTableRegistry {
    /** [shared] metatables are read by parallel workers: they are frozen once filled. */
    private class Entry(val name: String, val shared: Boolean = false, val fill: (LuaTable) -> Unit) {
        var table = LuaTable()
    }

    private val entries = listOf(
        Entry("vec", shared = true) { initVecMeta(it) },
        Entry("entity") { EntityWrap.initMeta(it) },
        Entry("player") { PlayerWrap.initMeta(it) },
        Entry("world") { WorldWrap.initMeta(it) },
        Entry("structure") { StructureWrap.initMeta(it) },
        Entry("item") { ItemStackWrap.initMeta(it) },
        Entry("inventory") { InvWrap.initMeta(it) },
        Entry("container") { ContainerWrapper.initMeta(it) },
        Entry("sidebar") { SidebarWrapper.initMeta(it) },
        Entry("mob") { MobWrap.initMeta(it) },
        Entry("hologram") { HologramWrapper.initMeta(it) },
        Entry("region") { RegionWrap.initMeta(it) },
        Entry("bossbar") { BossBarWrapper.initMeta(it) },
    )

    private val byName: Map<String, Entry> = entries.associateBy { it.name }

    private fun table(name: String): LuaTable = byName.getValue(name).table

    val ENTITY: LuaTable get() = table("entity")
    val PLAYER: LuaTable get() = table("player")
    val WORLD: LuaTable get() = table("world")
    val STRUCTURE: LuaTable get() = table("structure")
    val ITEM: LuaTable get() = table("item")
    val VEC: LuaTable get() = table("vec")
    val INVENTORY: LuaTable get() = table("inventory")
    val CONTAINER: LuaTable get() = table("container")
    val SIDEBAR: LuaTable get() = table("sidebar")
    val MOB: LuaTable get() = table("mob")
    val HOLOGRAM: LuaTable get() = table("hologram")
    val REGION: LuaTable get() = table("region")
    val BOSS_BAR: LuaTable get() = table("bossbar")

    /** Builds every table first and assigns them together, so a failing fill leaves the previous tables intact. */
    fun init() {
        val built = entries.map { e ->
            LuaTable().also {
                e.fill(it)
                if (e.shared) it.freeze()
            }
        }
        entries.forEachIndexed { i, e -> e.table = built[i] }
    }

    fun get(name: String): LuaTable = byName[name]?.table
        ?: throw IllegalArgumentException("Unknown metatable type: '$name'")
}
