package ru.pyxiion.ignis.mapcanvas

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapId
import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.ignis.unwrapOrNull
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import java.util.Optional

/** `require("ext:mapcanvas")` for one environment. */
class MapCanvasModule(private val m: MapCanvasEnv) {
    private val env = m.env

    private val wallMeta: LuaTable = env.metaTable(
        metaTable<Wall> {
            method("remove") { args ->
                wallOf(args).remove()
                LuaValue.NONE
            }
            toString { "MapWall" }
        },
    )

    private val canvasMeta: LuaTable = env.metaTable(
        metaTable<Canvas> {
            prop("id") { LuaValue.valueOf(id) }
            prop("viewers") {
                LuaTable().also { t ->
                    var i = 1
                    for (u in viewers) m.server.playerList.getPlayer(u)?.let { t.rawset(i++, env.convert.wrap(it)) }
                }
            }
            method("fill") { args ->
                val c = canvasOf(args)
                c.buffer.fill(args.arg(2).checkint(), args.arg(3).checkint(), args.arg(4).checkint(), args.arg(5).checkint(), color(args.arg(6), "color"))
                LuaValue.NONE
            }
            method("pixel") { args ->
                canvasOf(args).buffer.pixel(args.arg(2).checkint(), args.arg(3).checkint(), color(args.arg(4), "color"))
                LuaValue.NONE
            }
            method("blit") { args ->
                val c = canvasOf(args)
                val s = args.arg(6).checkstring()
                val w = args.arg(4).checkint()
                val h = args.arg(5).checkint()
                if (w < 0 || h < 0 || w.toLong() * h > s.m_length) {
                    throw LuaError("canvas:blit: ${w}x$h needs ${w.toLong() * h} bytes, got ${s.m_length}")
                }
                c.buffer.blit(args.arg(2).checkint(), args.arg(3).checkint(), w, h, s.m_bytes, s.m_offset)
                LuaValue.NONE
            }
            method("clear") { args ->
                canvasOf(args).buffer.clear(if (args.arg(2).isnil()) 0 else color(args.arg(2), "color"))
                LuaValue.NONE
            }
            method("icons") { args ->
                canvasOf(args).setIcons(parseIcons(args.arg(2)))
                LuaValue.NONE
            }
            method("show") { args ->
                canvasOf(args).viewers.add(env.convert.toPlayer(args.arg(2), "player").uuid)
                LuaValue.NONE
            }
            method("hide") { args ->
                canvasOf(args).viewers.remove(env.convert.toPlayer(args.arg(2), "player").uuid)
                LuaValue.NONE
            }
            method("flush") { args ->
                canvasOf(args).flush()
                LuaValue.NONE
            }
            method("item") { args ->
                val c = canvasOf(args)
                val stack = ItemStack(Items.FILLED_MAP)
                stack.set(DataComponents.MAP_ID, MapId(c.id))
                if (!args.arg(2).isnil()) stack.set(DataComponents.CUSTOM_NAME, Component.literal(args.arg(2).checkjstring()))
                env.convert.wrap(stack)
            }
            method("wall") { args -> wall(canvasOf(args), args) }
            method("free") { args ->
                m.free(canvasOf(args))
                LuaValue.NONE
            }
            toString { "MapCanvas($id)" }
        },
    )

    private fun canvasOf(args: Varargs): Canvas {
        val c = args.arg(1).unwrapOrNull<Canvas>() ?: throw LuaError("expected a canvas")
        if (c.freed) throw LuaError("canvas ${c.id} is freed")
        return c
    }

    private fun wallOf(args: Varargs): Wall = args.arg(1).unwrapOrNull<Wall>() ?: throw LuaError("expected a wall")

    private fun color(v: LuaValue, arg: String): Int {
        if (v.type() != LuaValue.TNUMBER) throw LuaError("bad argument '$arg': palette byte expected, got ${v.typename()}")
        val c = v.toint()
        if (c !in 0..255) throw LuaError("bad argument '$arg': palette byte must be 0..255, got $c")
        return c
    }

    private fun byte(t: LuaTable, key: String, lo: Int, hi: Int, default: Int?, fn: String): Int {
        val v = t.get(key)
        if (v.isnil()) return default ?: throw LuaError("$fn: icon field '$key' is required")
        if (v.type() != LuaValue.TNUMBER) throw LuaError("$fn: icon field '$key' must be a number, got ${v.typename()}")
        val n = v.toint()
        if (n !in lo..hi) throw LuaError("$fn: icon field '$key' must be $lo..$hi, got $n")
        return n
    }

    private fun parseIcons(list: LuaValue): List<MapDecoration> {
        if (list.isnil()) return emptyList()
        val t = list.checktable()
        val out = ArrayList<MapDecoration>()
        var i = 1
        while (true) {
            val item = t.get(i)
            if (item.isnil()) break
            if (!item.istable()) throw LuaError("canvas:icons: entry $i must be a table, got ${item.typename()}")
            val e = item.checktable()
            val typeName = e.get("type").let { if (it.type() != LuaValue.TSTRING) throw LuaError("canvas:icons: entry $i needs a string 'type'") else it.tojstring() }
            val holder = Identifier.tryParse(typeName)?.let { BuiltInRegistries.MAP_DECORATION_TYPE.get(it) }?.orElse(null)
                ?: throw LuaError("canvas:icons: unknown icon type '$typeName'")
            val name = e.get("name")
            out.add(
                MapDecoration(
                    holder,
                    byte(e, "x", -128, 127, null, "canvas:icons").toByte(),
                    byte(e, "z", -128, 127, null, "canvas:icons").toByte(),
                    byte(e, "rot", 0, 15, 0, "canvas:icons").toByte(),
                    if (name.isnil()) Optional.empty() else Optional.of(Component.literal(name.checkjstring())),
                ),
            )
            i++
        }
        return out
    }

    private fun wall(c: Canvas, args: Varargs): Varargs {
        val level = env.convert.toLevel(args.arg(2), "world")
        val vec = env.convert.toVec(args.arg(3), "pos")
        val facing = args.arg(4).checkjstring().let { s ->
            Direction.byName(s.lowercase())?.takeIf { it.axis.isHorizontal } ?: throw LuaError("canvas:wall: facing must be north, south, east or west, got '$s'")
        }
        val cols = args.arg(5).checkint()
        val rows = args.arg(6).checkint()
        if (cols < 1 || rows < 1) throw LuaError("canvas:wall: cols and rows must be positive")
        val list = args.arg(7).checktable()
        val canvases = ArrayList<Canvas>()
        for (i in 1..cols * rows) {
            val v = list.get(i)
            val cv = v.unwrapOrNull<Canvas>() ?: throw LuaError("canvas:wall: canvases[$i] must be a canvas (need ${cols * rows}), got ${v.typename()}")
            if (cv.freed) throw LuaError("canvas:wall: canvases[$i] is freed")
            canvases.add(cv)
        }
        val wall = Wall(m, level, BlockPos.containing(vec), facing, cols, rows, canvases)
        m.walls.add(wall)
        return env.newObject(wallMeta, "wall", wall)
    }

    fun build(): LuaValue {
        val t = LuaTable()
        t.rawset("canvas", env.function { env.newObject(canvasMeta, "canvas", m.newCanvas()) })
        t.rawset("color", env.function { args ->
            val shade = if (args.arg(2).isnil()) 1 else args.arg(2).checkint()
            try {
                LuaValue.valueOf(Palette.color(args.arg(1).checkjstring(), shade))
            } catch (e: IllegalArgumentException) {
                throw LuaError("mapcanvas.color: ${e.message}")
            }
        })
        t.rawset("rgb", env.function { args ->
            try {
                LuaValue.valueOf(Palette.rgb(args.arg(1).checkint(), args.arg(2).checkint(), args.arg(3).checkint()))
            } catch (e: IllegalArgumentException) {
                throw LuaError("mapcanvas.rgb: ${e.message}")
            }
        })
        return t
    }
}
