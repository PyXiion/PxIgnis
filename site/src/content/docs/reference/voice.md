---
title: Voice plugin (ext:voice)
description: Play .ogg and .wav files to players through Simple Voice Chat, with emitters, volume categories and no resource pack.
---

`ignis-voice` is a PxIgnis [plugin](/reference/packages/): a separate mod jar that goes next to PxIgnis in `mods/`.
It sends audio files to players through their voice chat mod, so no resource pack is needed.

```lua
local voice = require("ext:voice")
```

Without the plugin, `require("ext:voice")` fails with `plugin 'voice' is not installed`; use
`pcall(require, "ext:voice")` for scripts that can run without it.

## Requirements and limits

- Server mod [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 2.6.27 or newer on the server **and**
  installed by the player. Players without it hear nothing: check `voice.canHear(player)` and fall back to vanilla
  sounds.
- Formats: `.ogg` (Vorbis) and `.wav`. No mp3, seek or fades.
- Audio is converted to 48 kHz mono 16-bit, the voice chat format. Files up to 30 s are decoded once and cached in
  memory (keyed by path and modification time, kept across `/ignis reload`); longer files stream from disk.
- Decoding runs in the background and never blocks the server thread.
- Only Simple Voice Chat is supported for now. `voice.backends` lists what is installed.

## voice.backends, voice.canHear

```lua
voice.backends         -- {"svc"}; an empty table means nobody can hear anything
voice.canHear(player)  -- true if the player is connected to a voice chat
```

## voice.category

A volume category is a slider in the player's voice chat settings. Declare it in the script; it is registered again
on every reload.

```lua
voice.category{ id = "music", name = "Music", description = "Background music", icon = "icons/music.png" }
```

| Field         | Description                                                       |
|---------------|-------------------------------------------------------------------|
| `id`          | Required. `[a-z0-9_-]+`.                                          |
| `name`        | Slider label. Default: the id.                                    |
| `description` | Tooltip.                                                          |
| `icon`        | PNG path (see [paths](#paths)); scaled to 16×16.                  |

## voice.load

```lua
local boss = voice.load("sounds/boss.ogg")
boss.ready      -- boolean
boss.error      -- string or nil: why decoding failed
boss.duration   -- seconds, nil until ready
boss:onReady(function(s) ... end)  -- on the server thread, once decoded (not called on failure)
boss:await()                        -- yields until decoded; only inside coroutines
```

`load` returns at once and never yields. A file that does not exist or lies outside the allowed folder is an error
in the `load` call. A decode failure is reported once through the usual script error channel and sets `boss.error`.
`await` raises that error. It only works inside a coroutine (event handlers, `async` tasks); in the script root it
errors, so use `onReady` there.

## voice.emitter

A long-lived source.

```lua
local radio = voice.emitter{
    entity = npc,             -- or  pos = vec(...), world = w   -- or  players = { p1, p2 }
    distance = 32,
    category = "music",
    volume = 0.8,
    listeners = function(p) return p:hasTag("radio") end,
}
radio:play(boss, { loop = false })   -- replaces the current track
radio:queue(other)
radio:stop()
radio.entity = otherNpc              -- moves an entity emitter
radio.playing   radio.position       -- position in seconds in the current track
radio:onEnd(function() end)          -- the queue ran out (not after stop())
radio:close()
```

| Option      | Description                                                                                                   |
|-------------|---------------------------------------------------------------------------------------------------------------|
| `entity`    | The sound follows the entity and stops when the entity is removed.                                            |
| `pos`+`world` | A fixed point.                                                                                              |
| `players`   | Non-spatial: these players hear it at full volume.                                                            |
| `distance`  | Range in blocks, default 16. Ignored for `players`.                                                           |
| `category`  | A category declared with `voice.category`.                                                                    |
| `volume`    | 0 and up, default 1. Applied to the audio itself, since Simple Voice Chat has no per-source volume.           |
| `listeners` | A function `(player) -> boolean` or a list of players. Re-evaluated about once a second.                      |

Exactly one of `entity`, `pos`+`world` and `players` is required. `play` on a sound that is still decoding starts
playback when it is ready; on a sound that failed it raises an error. Calling `play`, `queue`, `stop` or `close`
never yields. `stop()` ends playback but keeps the emitter; `close()` frees it.

## voice.play

Fire and forget: a temporary emitter that closes itself at the end of the sound.

```lua
local h = voice.play(boss, { pos = vec(0, 64, 0), world = w, distance = 16, category = "music", loop = false })
h:stop()
```

It takes the emitter options plus `loop`.

## Paths

Paths of `voice.load` and `icon` resolve against the folder of the calling [package](/reference/packages/), or against
the script root for loose scripts, and cannot leave that folder.

## Reload

On `/ignis reload` all emitters stop, pending decodes are cancelled and categories are registered again. Decoded
audio stays cached.
