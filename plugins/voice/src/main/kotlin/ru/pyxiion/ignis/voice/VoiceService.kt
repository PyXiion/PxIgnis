package ru.pyxiion.ignis.voice

/** What outlives a script reload: the backends and the decoded-audio cache. */
class VoiceService(val backends: List<VoiceBackend>) {
    val cache = SoundCache()
}
