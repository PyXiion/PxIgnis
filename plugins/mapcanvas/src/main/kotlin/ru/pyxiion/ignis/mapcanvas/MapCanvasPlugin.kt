package ru.pyxiion.ignis.mapcanvas

import ru.pyxiion.ignis.plugin.IgnisPlugin
import ru.pyxiion.ignis.plugin.PluginEnv

/** The `pxignis` entrypoint: `require("ext:mapcanvas")`. */
class MapCanvasPlugin : IgnisPlugin {
    override val id = "mapcanvas"

    override fun onEnvironment(env: PluginEnv) {
        val e = MapCanvasEnv(env)
        env.onClose { e.close() }
        env.module { MapCanvasModule(e).build() }
    }
}
