---@meta

-- =============================================================================
-- PxIgnis global type declarations for LuaLS / sumneko.lua-language-server
-- =============================================================================
-- These declarations describe the API surface exposed to Lua scripts in
-- `config/ignis/*.lua`. They are loaded automatically by the workspace-level
-- `.luarc.json` (which lists this directory as a `workspace.library`).
--
-- Conventions:
--   * Wrapper classes use their `__pxrp_type` string as the Lua class name
--     (e.g. `Player`, `Entity`, `World`, `Item`, `Region`, ...).
--   * Methods that mutate state return `nil`. Read methods return their value.
--   * The runtime injects these as Lua globals: `mc`, `vec`, `register`.
--   * All PxIgnis APIs are read synchronously unless documented as async
--     (async APIs live in the `async` module: `require "async"`).
--
-- See https://ignis.pyxiion.ru for the human-readable reference.

---@alias PxIgnisEventName
---| 'init'             # scripts loaded (also after /ignis reload)
---| 'uninit'           # before scripts are unloaded
---| 'server_start'
---| 'server_stop'
---| 'tick'             # every server tick
---| 'player.login'     # cancellable: e:cancel("kick message")
---| 'player.join'
---| 'player.leave'
---| 'player.respawn'
---| 'player.chat'      # cancellable
---| 'player.move'
---| 'player.use_item'  # cancellable
---| 'player.attack'    # cancellable
---| 'player.interact'  # cancellable
---| 'player.kill'
---| 'player.consume'   # cancellable
---| 'player.pickup'    # cancellable
---| 'player.drop'      # cancellable
---| 'block.break'      # cancellable
---| 'block.place'      # cancellable
---| 'entity.spawn'
---| 'entity.despawn'
---| 'entity.hurt'      # cancellable, before damage is applied
---| 'entity.damaged'   # after damage is applied
---| 'entity.death'     # cancellable

---@alias RegionEventName
---| 'enter'    # an entity (or player: e.player) entered the region
---| 'leave'
---| 'move'     # moved inside the region
---| 'death'    # died inside the region
---| 'tick'
---| 'destroy'

---@class SidebarConfig
---@field title? string
---@field lines? string[]
---@field visible? boolean

---@class ParticleOpts
---@field count? integer
---@field spread? Vec|Vec3Like|number
---@field speed? number
---@field block? string    -- block particles: block, block_marker, falling_dust, dust_pillar, block_crumble
---@field power? number   -- dragon_breath, effect, instant_effect (default 1.0)
---@field color? table    -- dust, dust_color_transition, effect, instant_effect -> {r,g,b}; entity_effect, tinted_leaves, flash -> {a,r,g,b}
---@field fromColor? table -- dust_color_transition -> {r,g,b}
---@field toColor? table   -- dust_color_transition -> {r,g,b}
---@field scale? number   -- dust, dust_color_transition (default 1.0)
---@field roll? number    -- sculk_charge (default 0.0)
---@field delay? integer  -- shriek (default 0)
---@field target? Vec|Vec3Like -- trail -> vec
---@field duration? integer -- trail (default 20)
---@field from? Vec|Vec3Like -- vibration -> vec
---@field arrivalInTicks? integer -- vibration (default 1)
---@field item? string    -- item -> item id (count uses the top-level `count` field)

---@class BlockStateProps table<string, string>

---@class BlockStateTable
---@field id string
---@field properties? BlockStateProps

---@class RegionBoundsTable
---@field A Vec
---@field B Vec

---@class StructurePlacementParams
---@field rotation? '"none"'|'"0"'|'"clockwise_90"'|'"90"'|'"clockwise_180"'|'"180"'|'"counterclockwise_90"'|'"270"'
---@field mirror? '"none"'|'"left_right"'|'"front_back"'
---@field on_entity? fun(entity: Entity):boolean|nil

---@class ExecuteOpts
---@field as? Entity|Player
---@field at? Vec|Vec3Like

---@class FetchRequest
---@field url string
---@field method? string  -- HTTP method, default "GET"
---@field headers? table<string, string>
---@field body? string    -- raw string body
---@field json? any       -- JSON-encoded body (mutually exclusive with `body`)
---@field timeout? number -- seconds

---@class FetchResponse
---@field ok boolean
---@field status integer
---@field text string
---@field headers table<string, string>
---@field json any         -- lazily parsed; errors if body is not valid JSON
local FetchResponse = {}

---@class FetchError
---@field ok false
---@field error string

---@alias FetchResult FetchResponse | FetchError

---@alias Identifier string -- namespaced id, e.g. "minecraft:stone"

---@alias BossBarColor
---| 'pink'
---| 'blue'
---| 'red'
---| 'green'
---| 'yellow'
---| 'purple'
---| 'white'

---@alias BossBarStyle
---| 'progress'
---| 'notched_6'
---| 'notched_10'
---| 'notched_12'
---| 'notched_20'

---@alias Gamemode
---| 'survival'
---| 'creative'
---| 'adventure'
---| 'spectator'

---@alias Hand
---| 'main'
---| 'off'

---@alias HologramAlignment
---| 'left'
---| 'center'
---| 'right'

---@alias HologramBillboard
---| 'fixed'
---| 'vertical'
---| 'horizontal'
---| 'center'

---@class HologramOpts
---@field alignment? HologramAlignment
---@field billboard? HologramBillboard
---@field lineWidth? integer
---@field background? integer  -- ARGB
---@field opacity? integer     -- 0-255
---@field shadow? boolean
---@field seeThrough? boolean
---@field glowing? boolean
