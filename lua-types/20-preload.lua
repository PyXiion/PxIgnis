---@meta

-- Preload scripts: files named `*.preload.lua`, at any depth of a script root. They run once, at server start,
-- before any world exists, in their own Lua environment with `preload`, `print`, the plain Lua standard library
-- and `require`. There is NO `mc`, no events, no worlds and no players there. `/ignis reload` does not run them
-- again (it only warns that a restart is required when one changed).
--
-- The `preload` global exists ONLY in preload files. This stub declares it for the editor; using it in a normal
-- script is a runtime error ("global 'preload' is not defined").

---@class PreloadFlatGenerator
---@field flat { [1]: string, [2]: integer }[]  -- { block id, layer thickness } pairs, bottom-up from min_y

---@class PreloadSky
---@field time? "day"|"night" -- sky stuck at noon or midnight; nil = normal overworld day cycle
---@field ambient_light? number -- 0..1, default 0.0
---@field skylight? boolean     -- default true
---@field ceiling? boolean      -- default false

---@class PreloadDimensionOptions
---@field generator? "void"|PreloadFlatGenerator -- default "void"
---@field biome? string    -- default "minecraft:the_void"; must exist when the world loads
---@field min_y? integer   -- multiple of 16, -2032..2031; default 0
---@field height? integer  -- multiple of 16, 16..4064, min_y + height <= 2032; default 256
---@field sky? PreloadSky

---@class Preload
preload = {}

---Declares a custom dimension. `id` must be namespaced (`ns:name`) and not in the `minecraft` namespace.
---Unknown option keys, invalid values and a second declaration of the same id are errors; an error in a preload
---file drops all declarations of that file. After start the dimension is available as `mc.world(id)` and as the
---`world` argument of `player:teleport`.
---@param id string
---@param options? PreloadDimensionOptions
function preload.dimension(id, options) end
