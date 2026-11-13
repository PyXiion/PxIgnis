package ru.pyxiion.ignis.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.server.level.ServerPlayer
import ru.pyxiion.ignis.plugin.PluginEnv
import ru.pyxiion.ignis.runtime.ScriptWatchdog
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaValue
import java.nio.file.Path

/** Everything allocated for one Lua environment. Released by [close] on reload and server stop. */
class VoiceEnv(val service: VoiceService, val env: PluginEnv) {
    private var closed = false
    private val sounds = HashMap<SoundKey, Sound>()
    private val emitters = LinkedHashSet<Emitter>()
    private val jobs = HashSet<Job>()
    val categories = HashSet<String>()

    private val ticker: Job = env.scope.launch {
        while (isActive) {
            delay(REFRESH_MILLIS)
            env.runOnServer { refresh() }
        }
    }

    fun runOnServer(block: () -> Unit) = env.runOnServer { if (!closed) block() }

    fun onlinePlayers(): List<ServerPlayer> = env.server.playerList.players

    /** Server thread. [path] is already resolved and sandboxed. */
    fun load(path: Path, display: String): Sound {
        val key = SoundKey.of(path)
        sounds[key]?.let { return it }
        val sound = Sound(display)
        sounds[key] = sound
        val cached = service.cache[key]
        if (cached != null) {
            sound.complete(cached)
            return sound
        }
        val job = env.scope.launch(Dispatchers.IO) {
            val result = runCatching { SoundData.decode(path) }
            runOnServer {
                result.onSuccess {
                    service.cache.put(key, it)
                    sound.complete(it)
                }.onFailure {
                    val message = it.message ?: it.toString()
                    sound.fail(message)
                    sounds.remove(key)
                    env.errors.error("voice.load '$display'", message)
                }
            }
        }
        jobs.add(job)
        job.invokeOnCompletion { runOnServer { jobs.remove(job) } }
        return sound
    }

    fun addEmitter(e: Emitter) {
        emitters.add(e)
    }

    fun forget(e: Emitter) {
        emitters.remove(e)
    }

    private fun refresh() {
        for (e in emitters.toList()) {
            try {
                e.refresh()
            } catch (t: Throwable) {
                env.errors.error("voice emitter refresh", t)
            }
        }
    }

    fun call(context: String, fn: LuaFunction, vararg args: LuaValue) {
        try {
            ScriptWatchdog.guard { fn.invoke(LuaValue.varargsOf(args)) }
        } catch (t: Throwable) {
            env.errors.error(context, t)
        }
    }

    /** A listener predicate: an error counts as "no". */
    fun callBoolean(context: String, fn: LuaFunction, player: ServerPlayer): Boolean = try {
        ScriptWatchdog.guard { fn.call(env.convert.wrap(player)).toboolean() }
    } catch (t: Throwable) {
        env.errors.error(context, t)
        false
    }

    fun close() {
        closed = true
        ticker.cancel()
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
        emitters.toList().forEach { it.close() }
        emitters.clear()
        categories.forEach { id -> service.backends.forEach { it.unregisterCategory(id) } }
        categories.clear()
    }

    private companion object {
        const val REFRESH_MILLIS = 1000L
    }
}
