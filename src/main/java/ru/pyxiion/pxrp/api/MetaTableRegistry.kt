package ru.pyxiion.pxrp.api

import org.luaj.vm2.LuaTable

object MetaTableRegistry {
    val ENTITY = LuaTable()
    val PLAYER = LuaTable()
    val WORLD = LuaTable()
    val STRUCTURE = LuaTable()

    private val byName = mapOf(
        "entity" to ENTITY,
        "player" to PLAYER,
        "world" to WORLD,
        "structure" to STRUCTURE,
    )

    fun get(name: String): LuaTable = byName[name]
        ?: throw IllegalArgumentException("Unknown metatable type: '$name'")
}
