package ru.pyxiion.ignis.voice

import net.fabricmc.loader.api.FabricLoader
import ru.pyxiion.ignis.plugin.IgnisPlugin
import ru.pyxiion.ignis.plugin.PluginContext
import ru.pyxiion.ignis.plugin.PluginEnv

/** The `pxignis` entrypoint: `require("ext:voice")`. */
class VoicePlugin : IgnisPlugin {
    override val id = "voice"

    private var service: VoiceService? = null

    override fun onServerStart(ctx: PluginContext) {
        service = VoiceService(Backends.detect())
    }

    override fun onEnvironment(env: PluginEnv) {
        val service = service ?: return
        val voiceEnv = VoiceEnv(service, env)
        env.onClose { voiceEnv.close() }
        env.module { VoiceModule(voiceEnv).build() }
    }

    override fun onServerStop() {
        service = null
    }
}

/** The only place that names a backend class, and only after its mod is known to be loaded. */
internal object Backends {
    fun detect(): List<VoiceBackend> {
        val found = ArrayList<VoiceBackend>()
        if (FabricLoader.getInstance().isModLoaded("voicechat")) found.add(svc())
        return found
    }

    private fun svc(): VoiceBackend = ru.pyxiion.ignis.voice.svc.SvcBackend
}
