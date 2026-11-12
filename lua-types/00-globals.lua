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
--     (the only async APIs are `mc.fetch` and `mc.sleep`).
--
-- See https://ignis.pyxiion.ru for the human-readable reference.

---@alias PxIgnisEventName
---| 'server_start'           -- ()                                       not cancellable
---| 'server_stop'            -- ()                                       not cancellable
---| 'init'                   -- ()                                       not cancellable
---| 'uninit'                 -- ()                                       not cancellable
---| 'player_join_init'       -- (player)                                cancellable (return false)
---| 'player_join'            -- (player)                                not cancellable
---| 'player_respawn'         -- (player, alive: boolean)                not cancellable
---| 'player_leave'           -- (player)                                not cancellable
---| 'player_death'           -- (player, amount: number, msgId: string, attacker: Entity|nil) cancellable
---| 'player_chat'            -- (player, message: string)               cancellable
---| 'player_block_break'     -- (player, pos: Vec, blockId: string)     cancellable
---| 'player_block_place'     -- (player, pos: Vec, blockId: string)     cancellable
---| 'player_use_item'        -- (player, hand: string, item: Item|nil, itemId: string) cancellable
---| 'player_attack_entity'   -- (player, entity: Entity)                cancellable
---| 'player_interact_entity' -- (player, entity: Entity, hand: string)  cancellable
---| 'player_hurt'            -- (player, amount: number, msgId: string, attacker: Entity|nil) cancellable
---| 'entity_hurt'            -- (entity: Entity, amount: number, msgId: string, attacker: Entity|nil) cancellable
---| 'player_damage'          -- (player, damageType: string, amount: number, blocked: boolean) not cancellable
---| 'entity_damage'          -- (entity: Entity, damageType: string, amount: number, source: Entity|nil, blocked: boolean) not cancellable
---| 'player_kill'            -- (player, target: Entity, damageSource: string) not cancellable
---| 'entity_spawn'           -- (entity: Entity)                        not cancellable
---| 'entity_despawn'         -- (entity: Entity)                        not cancellable
---| 'entity_death'           -- (entity: Entity, amount: number, msgId: string, attacker: Entity|nil) cancellable
---| 'tick'                   -- ()                                       not cancellable

---@alias RegionEventName
---| 'entity_enter'  -- (entity: Entity)
---| 'entity_leave'  -- (entity: Entity)
---| 'entity_move'   -- (entity: Entity, from: Vec, to: Vec)
---| 'player_enter'  -- (player: Player)
---| 'player_leave'  -- (player: Player)
---| 'player_move'   -- (player: Player, from: Vec, to: Vec)
---| 'entity_death'  -- (entity: Entity, source: string, amount: number)
---| 'player_death'  -- (player: Player, source: string)
---| 'tick'          -- ()
---| 'destroy'       -- ()

---@class SidebarConfig
---@field title? string
---@field lines? string[]
---@field visible? boolean

---@class EmitOpts
---@field count? integer             -- particles per emit, >= 1 (default 1); not with `velocity`
---@field spread? Vec|Vec3Like|number -- random offset per axis; not with `velocity`
---@field speed? number               -- default 0, or 1 with `velocity`
---@field velocity? Vec|Vec3Like      -- spawns one particle moving along this vector (times `speed`)
---@field alwaysShow? boolean         -- shown even on "Minimal" particle settings (default false)
---@field viewers? Player|Player[]    -- only these players see it (default: everyone in range)

---@class ParticleData
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
---@field item? string    -- item -> item id
---@field count? integer  -- item -> item stack size

---Immutable, pre-built particle. Create once with `particle()`, send with `World:emit`.
---@class Particle
---@field id string

---Builds a particle. Errors for an unknown id, or when a type that needs `data` gets none.
---@param id string
---@param data? ParticleData
---@return Particle
function particle(id, data) end

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
