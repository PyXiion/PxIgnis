package ru.pyxiion.ignis.api

import org.luaj.vm2.LuaTable
import ru.pyxiion.ignis.api.wrapper.*

object MetaTableRegistry {
    private var _entity = LuaTable()
    private var _player = LuaTable()
    private var _world = LuaTable()
    private var _structure = LuaTable()
    private var _item = LuaTable()
    private var _vec = LuaTable()
    private var _inventory = LuaTable()
    private var _container = LuaTable()
    private var _sidebar = LuaTable()
    private var _mob = LuaTable()
    private var _hologram = LuaTable()
    private var _region = LuaTable()
    private var _bossBar = LuaTable()

    val ENTITY: LuaTable get() = _entity
    val PLAYER: LuaTable get() = _player
    val WORLD: LuaTable get() = _world
    val STRUCTURE: LuaTable get() = _structure
    val ITEM: LuaTable get() = _item
    val VEC: LuaTable get() = _vec
    val INVENTORY: LuaTable get() = _inventory
    val CONTAINER: LuaTable get() = _container
    val SIDEBAR: LuaTable get() = _sidebar
    val MOB: LuaTable get() = _mob
    val HOLOGRAM: LuaTable get() = _hologram
    val REGION: LuaTable get() = _region
    val BOSS_BAR: LuaTable get() = _bossBar

    private var byName = mapOf(
        "entity" to _entity,
        "player" to _player,
        "world" to _world,
        "structure" to _structure,
        "item" to _item,
        "vec" to _vec,
        "inventory" to _inventory,
        "container" to _container,
        "sidebar" to _sidebar,
        "mob" to _mob,
        "hologram" to _hologram,
        "region" to _region,
        "bossbar" to _bossBar
    )

    fun init() {
        _entity = LuaTable()
        _player = LuaTable()
        _world = LuaTable()
        _structure = LuaTable()
        _item = LuaTable()
        _vec = LuaTable()
        _inventory = LuaTable()
        _container = LuaTable()
        _sidebar = LuaTable()
        _mob = LuaTable()
        _hologram = LuaTable()
        _region = LuaTable()
        _bossBar = LuaTable()

        byName = mapOf(
            "entity" to _entity,
            "player" to _player,
            "world" to _world,
            "structure" to _structure,
            "item" to _item,
            "vec" to _vec,
            "inventory" to _inventory,
            "container" to _container,
            "sidebar" to _sidebar,
            "mob" to _mob,
            "hologram" to _hologram,
            "region" to _region,
            "bossbar" to _bossBar
        )

        initVecMeta(_vec)
        EntityWrap.initMeta(_entity)
        PlayerWrap.initMeta(_player)
        WorldWrap.initMeta(_world)
        StructureWrap.initMeta(_structure)
        ItemStackWrap.initMeta(_item)
        InvWrap.initMeta(_inventory)
        ContainerWrapper.initMeta(_container)
        SidebarWrapper.initMeta(_sidebar)
        MobWrap.initMeta(_mob)
        HologramWrapper.initMeta(_hologram)
        RegionWrap.initMeta(_region)
        BossBarWrapper.initMeta(_bossBar)
    }

    fun get(name: String): LuaTable = byName[name]
        ?: throw IllegalArgumentException("Unknown metatable type: '$name'")
}