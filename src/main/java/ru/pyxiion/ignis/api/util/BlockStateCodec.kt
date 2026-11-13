package ru.pyxiion.ignis.api.util

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.ignis.luaTableOf

@Suppress("UNCHECKED_CAST")
object BlockStateCodec {
    fun stateToTable(state: BlockState): LuaTable {
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block)
        val propsTable = LuaTable()

        for (prop in state.properties) {
            val value: Any = state.getValue(prop)
            val luaValue = when (prop) {
                is BooleanProperty -> LuaValue.valueOf(value as Boolean)
                is IntegerProperty -> LuaValue.valueOf(value as Int)
                else -> LuaValue.valueOf((prop as Property<Comparable<Any>>).getName(value as Comparable<Any>))
            }
            propsTable.set(prop.name, luaValue)
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
                ?: throw LuaError("Блок '${BuiltInRegistries.BLOCK.getKey(block)}' не имеет свойства '$propName'")

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
            if (!value.isboolean()) {
                throw LuaError("Свойство '${p.name}' ожидает boolean, получено ${value.typename()}")
            }
            return state.setValue(p, value.toboolean() as Comparable<Any>)
        }
        if (type == Int::class.javaPrimitiveType || type == Int::class.java) {
            if (value.type() != LuaValue.TNUMBER || value.todouble() != Math.floor(value.todouble())) {
                throw LuaError("Свойство '${p.name}' ожидает целое число, получено ${value.typename()}")
            }
            val n = value.toint()
            if (n as Comparable<Any> !in p.possibleValues) {
                throw LuaError("Недопустимое значение $n для свойства '${p.name}' (допустимо: ${p.possibleValues.joinToString()})")
            }
            return state.setValue(p, n as Comparable<Any>)
        }
        val strValue = value.tojstring()
        val parsed = p.getValue(strValue).orElseThrow {
            LuaError("Недопустимое значение '$strValue' для свойства '${p.name}'")
        }
        return state.setValue(p, parsed)
    }
}
