package ru.pyxiion.ignis.api.util

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.state.property.BooleanProperty
import net.minecraft.state.property.IntProperty
import net.minecraft.state.property.Property
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.luaTableOf

@Suppress("UNCHECKED_CAST")
object BlockStateCodec {
    fun stateToTable(state: BlockState): LuaTable {
        val blockId = Registries.BLOCK.getId(state.block)
        val propsTable = LuaTable()

        for (prop in state.properties) {
            val value: Any = state.get(prop)
            val luaValue = when (prop) {
                is BooleanProperty -> LuaValue.valueOf(value as Boolean)
                is IntProperty -> LuaValue.valueOf((value as Int).toDouble())
                else -> LuaValue.valueOf((prop as Property<Comparable<Any>>).name(value as Comparable<Any>))
            }
            propsTable.set(prop.name, luaValue)
        }

        return luaTableOf(
            "id" to LuaValue.valueOf(blockId.toString()),
            "properties" to propsTable,
        )
    }

    fun applyProperties(block: Block, propsTable: LuaTable): BlockState {
        var state = block.defaultState
        val stateManager = block.stateManager

        val keys = propsTable.keys()
        for (key in keys) {
            val propName = key.tojstring()
            if (propName == "id") continue

            val prop = stateManager.getProperty(propName)
                ?: throw LuaError("Блок '${Registries.BLOCK.getId(block)}' не имеет свойства '$propName'")

            val value = propsTable.get(key)
            state = applyProperty(state, prop, value)
        }

        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyProperty(state: BlockState, prop: Property<*>, value: LuaValue): BlockState {
        val type = prop.type
        val p = prop as Property<Comparable<Any>>

        if (type == Boolean::class.java) {
            return state.with(p, value.toboolean() as Comparable<Any>)
        }
        if (type == Int::class.javaPrimitiveType || type == Int::class.java) {
            return state.with(p, value.toint() as Comparable<Any>)
        }
        val strValue = value.tojstring()
        val parsed = p.parse(strValue).orElseThrow {
            LuaError("Недопустимое значение '$strValue' для свойства '${p.name}'")
        }
        return state.with(p, parsed)
    }
}
