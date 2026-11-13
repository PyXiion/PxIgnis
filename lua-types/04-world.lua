---@meta

-- World wrapper. Metatable name: `"world"`.

---@class World
local World = {}

---World id, e.g. "minecraft:overworld", "minecraft:the_nether".
---@type string
World.name = nil

---Time of day in ticks. Read/write.
---@type integer
World.time = nil

---@type boolean
World.raining = nil

---@type boolean
World.thundering = nil

---Live, per-tick list of Player wrappers in this world.
---@type Player[]
World.players = nil

---All regions defined in this world.
---@type Region[]
World.regions = nil

---@param entityId string
---@param pos Vec|Vec3Like
---@param overrides? { health?: number, custom_name?: string }
---@return Entity|nil
function World:spawn(entityId, pos, overrides) end

---@param pos Vec|Vec3Like
---@param text string
---@param opts? HologramOpts
---@return Hologram|nil
function World:spawnHologram(pos, text, opts) end

---Sets a block by id at a position.
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param blockId string
function World:setBlock(pos, blockId) end

---True when the block's collision shape at `pos` is not empty (the block stops a generic entity).
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return boolean
function World:isSolid(pos) end

---True when the block's collision shape at `pos` is empty (an entity can occupy it).
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return boolean
function World:isPassable(pos) end

---Whether the block at `pos` is in a block tag, e.g. "minecraft:climbable" (a leading `#` is optional).
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param tag string
---@return boolean
function World:hasBlockTag(pos, tag) end

---@class BreakBlockOptions
---@field drops? boolean   -- drop the block's items; default false
---@field effects? boolean -- break sound and particles; default true

---Destroys the block like a break (sound and particles, optional drops). Does not fire `block.break`.
---Returns false for air.
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param opts? BreakBlockOptions
---@return boolean
function World:breakBlock(pos, opts) end

---Shows the crack overlay on a block, `stage` 0..9; nil or -1 clears it. Clearing needs the same `sourceId`
---(a string or number; default: one per position) that set it.
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param stage integer|nil
---@param sourceId? string|integer
function World:setBreakProgress(pos, stage, sourceId) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return string  -- block id
function World:getBlock(pos) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return BlockStateTable
function World:getBlockState(pos) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param state BlockStateTable
function World:setBlockState(pos, state) end

---@class CaptureStructureOptions
---@field entities? boolean  -- also capture entities in the area; default false
---@field ignore? string     -- block id that is not saved (e.g. "minecraft:structure_void"); default none

---Captures the area between two inclusive corners (any order) as a Structure, block entities included (chests,
---structure blocks in DATA mode...). Not limited to 48 blocks, but to 500000 blocks of volume; chunks in the area
---are loaded. See `Structure:save`.
---@param posA Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param posB Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param opts? CaptureStructureOptions
---@return Structure
function World:captureStructure(posA, posB, opts) end

---Fills a box with the given block. Volume capped at 32768.
---@param posA Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param posB Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param blockId string
function World:fill(posA, posB, blockId) end

---Sends a particle at a position.
---@param particle Particle
---@param pos Vec|Vec3Like
---@param opts? EmitOpts
function World:emit(particle, pos, opts) end

---Sends a particle at each position; opts are parsed once.
---@param particle Particle
---@param positions (Vec|Vec3Like)[]
---@param opts? EmitOpts
function World:emitMany(particle, positions, opts) end

---@param soundId string
---@param pos Vec|Vec3Like
---@param volume? number
---@param pitch? number
function World:playSound(soundId, pos, volume, pitch) end

---Returns entities in a sphere around the position, optionally filtered by type.
---@param pos Vec|Vec3Like
---@param radius number
---@param typeFilter? string  -- e.g. "minecraft:zombie"
---@return Entity[]
function World:getEntities(pos, radius, typeFilter) end

---Raycasts in this world.
---@param start Vec|Vec3Like
---@param dir Vec|Vec3Like
---@param range number
---@param includeFluids? boolean
---@param includeEntities? boolean
---@return RaycastResult|nil
function World:raycast(start, dir, range, includeFluids, includeEntities) end

---Broadcasts a chat message to players within range.
---@param text string
---@param pos Vec|Vec3Like
---@param range number
---@param overlay? boolean  -- true: action bar instead of chat
function World:broadcastInRange(text, pos, range, overlay) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return string|nil  -- biome id, e.g. "minecraft:plains"
function World:getBiome(pos) end

---Returns a world border object.
---@return WorldBorder
function World:getBorder() end

---@param pos Vec|Vec3Like
---@param power number
---@param opts? { fire?: boolean, destruction?: '"break"'|'"none"' }
function World:explode(pos, power, opts) end

---Strikes lightning at a position.
---@param pos Vec|Vec3Like
---@param opts? { effect?: boolean }
function World:strike(pos, opts) end

---@param posA Vec|Vec3Like
---@param posB Vec|Vec3Like
---@return Region
function World:createRegion(posA, posB) end

---@param id integer
---@return Region|nil
function World:getRegion(id) end

---@param pos Vec|Vec3Like
---@return Region[]
function World:getRegionsAt(pos) end

---Resolves an entity selector.
---@param selector string  -- supports @a, @a[], @e, @e[type=...,distance=..N,limit=N,x=..,y=..,z=..]
---@param opts? { at?: Vec|Vec3Like, as?: Entity|Player }
---@return Entity[]
function World:getEntitiesBySelector(selector, opts) end

---@class WorldBorder
local WorldBorder = {}

---@type { x: number, z: number }
WorldBorder.center = nil
---@type number
WorldBorder.size = nil
---@type number
WorldBorder.damage = nil
---@type integer
WorldBorder.warningTime = nil
---@type integer
WorldBorder.warningBlocks = nil
---@type number
WorldBorder.damageThreshold = nil

---@param size number
function WorldBorder:setSize(size) end
