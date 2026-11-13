---@meta ext:voice

-- ignis-voice: play .ogg / .wav files to players through a voice chat mod (Simple Voice Chat).
-- local voice = require("ext:voice")

---@class VoiceCategorySpec
---@field id string                 # [a-z0-9_-]+
---@field name? string              # shown on the client slider; default: id
---@field description? string
---@field icon? string              # PNG path, scaled to 16x16

---@class VoiceSound
---@field ready boolean
---@field error string?             # why decoding failed
---@field duration number?          # seconds; nil until ready
local VoiceSound = {}

---Runs on the server thread once the sound is decoded (not at all if decoding failed).
---@param fn fun(sound: VoiceSound)
function VoiceSound:onReady(fn) end

---Yields until decoded. Only inside coroutines (handlers, async); raises if decoding failed.
---@return VoiceSound
function VoiceSound:await() end

---Exactly one source: `entity`, `pos` + `world`, or `players`.
---@class VoiceEmitterSpec
---@field entity? Entity
---@field pos? vec
---@field world? World
---@field players? Player[]          # non-spatial
---@field distance? number           # default 16
---@field category? string           # declared with voice.category
---@field volume? number             # 0 and up, default 1
---@field listeners? (fun(player: Player): boolean)|Player[]   # re-evaluated about once per second

---@class VoicePlayOptions: VoiceEmitterSpec
---@field loop? boolean

---@class VoiceEmitter
---@field playing boolean
---@field position number            # seconds into the current track
---@field entity Entity?             # settable on an emitter created with an entity
local VoiceEmitter = {}

---Replaces the current track.
---@param sound VoiceSound
---@param opts? { loop: boolean? }
function VoiceEmitter:play(sound, opts) end

---@param sound VoiceSound
function VoiceEmitter:queue(sound) end

function VoiceEmitter:stop() end

---Runs when the queue ran out.
---@param fn fun()
function VoiceEmitter:onEnd(fn) end

function VoiceEmitter:close() end

---@class VoiceModule
---@field backends string[]          # installed backends, e.g. {"svc"}; empty: nobody can hear anything
local voice = {}

---@param player Player
---@return boolean
function voice.canHear(player) end

---@param spec VoiceCategorySpec
function voice.category(spec) end

---Returns at once; never yields. Paths resolve against the calling package or the script root.
---@param path string
---@return VoiceSound
function voice.load(path) end

---@param spec VoiceEmitterSpec
---@return VoiceEmitter
function voice.emitter(spec) end

---Fire and forget: a temporary emitter that closes itself at the end. `h:stop()` ends it.
---@param sound VoiceSound
---@param opts VoicePlayOptions
---@return VoiceEmitter
function voice.play(sound, opts) end

return voice
