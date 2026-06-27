package ru.pyxiion.ignis.api.util

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.core.registries.BuiltInRegistries
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import ru.pyxiion.ignis.luaTableOf

@Suppress("UNCHECKED_CAST")
object BlockStateCodec {
    fun stateToTable(state: BlockState): LuaTable {
        val blockId = BuiltInRegistries.BLOCK.getId(state.block)
        val propsTable = LuaTable()

        for (prop in state.properties) {
            val value: Any = state.getValue(prop)
            val luaValue = when (prop) {
                is BooleanProperty -> LuaValue.valueOf(value as Boolean)
                is IntegerProperty -> LuaValue.valueOf((value as Int).toDouble())
                else -> LuaValue.valueOf((prop as Property<Comparable<Any>>).getName(value as Comparable<Any>))
            }
            propsTable.set(prop.getName(), luaValue)
        }

        return luaTableOf(
            "id" to LuaValue.valueOf(blockId.toString()),
            "properties" to propsTable,
        )
    }

    fun applyProperties(block: Block, propsTable: LuaTable): BlockState {
        var state = block.defaultBlockState()
        val stateManager = block.stateDefinition

        val keys = propsTable.keys()
        for (key in keys) {
            val propName = key.tojstring()
            if (propName == "id") continue

            val prop = stateManager.getProperty(propName)
                ?: throw LuaError("Блок '${BuiltInRegistries.BLOCK.getId(block)}' не имеет свойства '$propName'")

            val value = propsTable.get(key)
            state = applyProperty(state, prop, value)
        }

        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyProperty(state: BlockState, prop: Property<*>, value: LuaValue): BlockState {
        val type = prop.valueClass
        val p = prop as Property<Comparable<Any>>

        if (type == Boolean::class.java) {
            return state.setValue(p, value.toboolean() as Comparable<Any>)
        }
        if (type == Int::class.javaPrimitiveType || type == Int::class.java) {
            return state.setValue(p, value.toint() as Comparable<Any>)
        }
        val strValue = value.tojstring()
        val parsed = p.getValue(strValue).orElseThrow {
            LuaError("Недопустимое значение '$strValue' для свойства '${p.name}'")
        }
        return state.setValue(p, parsed)
    }
}
