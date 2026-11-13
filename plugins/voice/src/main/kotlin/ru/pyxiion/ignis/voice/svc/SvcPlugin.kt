package ru.pyxiion.ignis.voice.svc

import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent

/**
 * The `voicechat` entrypoint. Simple Voice Chat loads it only when it is installed, and hands over its server API
 * when the voice server starts.
 */
class SvcPlugin : VoicechatPlugin {
    override fun getPluginId(): String = "ignis-voice"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { SvcBackend.api = it.voicechat }
        registration.registerEvent(VoicechatServerStoppedEvent::class.java) { SvcBackend.api = null }
    }
}
