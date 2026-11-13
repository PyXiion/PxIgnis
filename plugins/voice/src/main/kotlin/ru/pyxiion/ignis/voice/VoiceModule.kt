package ru.pyxiion.ignis.voice

import ru.pyxiion.ignis.api.util.metaTable
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.ignis.plugin.Opts
import ru.pyxiion.ignis.unwrapOrNull
import java.io.IOException
import javax.imageio.ImageIO

/** `require("ext:voice")` for one environment. See docs/voice.md. */
class VoiceModule(private val v: VoiceEnv) {
    private val env = v.env
    private val backends = v.service.backends

    private val soundMeta: LuaTable
    private val emitterMeta: LuaTable

    init {
        soundMeta = env.metaTable(
            metaTable<Sound> {
                prop("ready") { LuaValue.valueOf(ready) }
                prop("error") { error?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL }
                prop("duration") { data?.let { LuaValue.valueOf(it.durationSeconds) } ?: LuaValue.NIL }
                method("onReady") { args ->
                    val sound = args.arg(1).unwrapOrNull<Sound>() ?: throw LuaError("expected a sound")
                    val fn = args.arg(2).checkfunction()
                    sound.whenSettled {
                        if (sound.ready) v.runOnServer { v.call("sound:onReady", fn, sound.lua) }
                    }
                    LuaValue.NONE
                }
                toString { "Sound(${name})" }
            },
        ).also { meta ->
            meta.rawset(
                "await",
                env.suspendFunction { args ->
                    val sound = args.arg(1).unwrapOrNull<Sound>() ?: throw LuaError("expected a sound")
                    sound.done.await()
                    sound.error?.let { throw LuaError("voice sound '${sound.name}' failed to load: $it") }
                    sound.lua
                },
            )
        }

        emitterMeta = env.metaTable(
            metaTable<Emitter> {
                prop("playing") { LuaValue.valueOf(playing) }
                prop("position") { LuaValue.valueOf(positionSeconds) }
                prop(
                    "entity",
                    { entity?.let { env.convert.wrap(it) } ?: LuaValue.NIL },
                    { setEntity(env.convert.toEntity(it, "entity")) },
                )
                method("play") { args ->
                    val e = emitterOf(args)
                    e.play(soundOf(args.arg(2), "sound"), loopOf(args.arg(3), "emitter:play"))
                    LuaValue.NONE
                }
                method("queue") { args ->
                    emitterOf(args).queue(soundOf(args.arg(2), "sound"))
                    LuaValue.NONE
                }
                method("stop") { args ->
                    emitterOf(args).stop()
                    LuaValue.NONE
                }
                method("close") { args ->
                    emitterOf(args).close()
                    LuaValue.NONE
                }
                method("onEnd") { args ->
                    emitterOf(args).onEnd = args.arg(2).checkfunction()
                    LuaValue.NONE
                }
                toString { "Emitter" }
            },
        )
    }

    private fun emitterOf(args: Varargs): Emitter =
        args.arg(1).unwrapOrNull<Emitter>() ?: throw LuaError("expected an emitter")

    private fun soundOf(v: LuaValue, arg: String): Sound =
        v.unwrapOrNull<Sound>() ?: throw LuaError("bad argument '$arg': sound expected, got ${v.typename()}")

    private fun loopOf(options: LuaValue, fn: String): Boolean =
        env.convert.options(options, fn).also { it.rejectUnknown("loop") }.boolean("loop", false)

    fun build(): LuaValue {
        val t = LuaTable()
        t.rawset("backends", LuaTable().also { list -> backends.forEachIndexed { i, b -> list.rawset(i + 1, LuaValue.valueOf(b.id)) } })
        t.rawset("canHear", env.function { args ->
            val p = env.convert.toPlayer(args.arg(1), "player")
            LuaValue.valueOf(backends.any { it.canHear(p) })
        })
        t.rawset("category", env.function { args -> category(args.arg(1)) })
        t.rawset("load", env.function { args -> load(args.arg(1)) })
        t.rawset("emitter", env.function { args ->
            val e = createEmitter(args.arg(1), "voice.emitter", closeOnEnd = false, extra = emptyArray())
            wrap(e)
        })
        t.rawset("play", env.function { args -> play(args.arg(1), args.arg(2)) })
        return t
    }

    private fun wrap(e: Emitter): LuaValue = env.newObject(emitterMeta, "emitter", e)

    private fun category(spec: LuaValue): Varargs {
        val opts = env.convert.options(spec, "voice.category")
        opts.rejectUnknown("id", "name", "description", "icon")
        val id = Options.checkCategoryId(opts.stringOrNull("id") ?: throw IllegalArgumentException("voice.category: option 'id' is required"))
        val name = opts.string("name", id)
        val description = opts.string("description", "")
        val icon = opts.stringOrNull("icon")?.let { rel ->
            val file = try {
                env.file(rel)
            } catch (e: IOException) {
                throw IllegalArgumentException("voice.category: icon '$rel': ${e.message}")
            }
            try {
                ImageIO.read(file.toFile()) ?: throw IllegalArgumentException("voice.category: icon '$rel' is not a readable image")
            } catch (e: IOException) {
                throw IllegalArgumentException("voice.category: icon '$rel': ${e.message}")
            }
        }
        backends.forEach { it.registerCategory(id, name, description, icon) }
        v.categories.add(id)
        return LuaValue.NONE
    }

    private fun load(pathArg: LuaValue): Varargs {
        val rel = pathArg.checkjstring()
        val path = try {
            env.file(rel)
        } catch (e: IOException) {
            throw IllegalArgumentException("voice.load: cannot open '$rel': ${e.message}")
        }
        val sound = try {
            v.load(path, rel)
        } catch (e: IOException) {
            throw IllegalArgumentException("voice.load: cannot read '$rel': ${e.message}")
        }
        val lua = env.newObject(soundMeta, "sound", sound)
        sound.lua = lua
        return lua
    }

    private fun play(soundArg: LuaValue, options: LuaValue): Varargs {
        val sound = soundOf(soundArg, "sound")
        val loop = env.convert.options(options, "voice.play").boolean("loop", false)
        val e = createEmitter(options, "voice.play", closeOnEnd = true, extra = arrayOf("loop"))
        e.play(sound, loop)
        return wrap(e)
    }

    private fun createEmitter(spec: LuaValue, fn: String, closeOnEnd: Boolean, extra: Array<String>): Emitter {
        val opts = env.convert.options(spec, fn)
        opts.rejectUnknown("entity", "pos", "world", "players", "distance", "category", "volume", "listeners", *extra)
        val kind = Options.sourceKind(fn, opts.has("entity"), opts.has("pos"), opts.has("world"), opts.has("players"))
        val source: Source = when (kind) {
            SourceKind.ENTITY -> Source.OfEntity(opts.entity("entity")!!)
            SourceKind.POSITION -> {
                val p = opts.vec("pos")!!
                Source.OfPos(opts.level("world")!!, p.x, p.y, p.z)
            }
            SourceKind.PLAYERS -> {
                val players = opts.list("players").mapIndexed { i, pv -> env.convert.toPlayer(pv, "players[${i + 1}]") }
                Source.OfPlayers(players)
            }
        }
        val distance = Options.checkDistance(fn, opts.float("distance", DEFAULT_DISTANCE))
        val volume = Options.checkVolume(fn, opts.float("volume", 1f))
        val category = opts.stringOrNull("category")
        if (category != null && category !in v.categories) {
            throw IllegalArgumentException("$fn: unknown category '$category': declare it with voice.category first")
        }
        val listeners = listenersOf(spec, fn)
        val emitter = Emitter(v, backends, source, distance, category, volume, listeners, closeOnEnd)
        v.addEmitter(emitter)
        return emitter
    }

    /** `listeners` is a function or a list of players, which [Opts] cannot say apart, so it is read raw. */
    private fun listenersOf(spec: LuaValue, fn: String): Listeners? {
        if (!spec.istable()) return null
        val raw = spec.checktable().get("listeners")
        return when {
            raw.isnil() -> null
            raw.isfunction() -> Listeners.Fn(raw.checkfunction())
            raw.istable() -> {
                val ids = HashSet<java.util.UUID>()
                var i = 1
                while (true) {
                    val item = raw.get(i)
                    if (item.isnil()) break
                    ids.add(env.convert.toPlayer(item, "listeners[$i]").uuid)
                    i++
                }
                Listeners.Fixed(ids)
            }
            else -> throw IllegalArgumentException("$fn: option 'listeners' must be a function or a list of players, got ${raw.typename()}")
        }
    }

    private companion object {
        const val DEFAULT_DISTANCE = 16f
    }
}
