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

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return string  -- block id
function World:getBlock(pos) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@return BlockStateTable|nil  -- nil for air
function World:getBlockState(pos) end

---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param state BlockStateTable
function World:setBlockState(pos, state) end

---Fills a box with the given block. Volume capped at 32768.
---@param posA Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param posB Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param blockId string
function World:fill(posA, posB, blockId) end

---Spawns a particle at a position.
---@param particleId string
---@param pos Vec|Vec3Like
---@param opts? ParticleOpts
function World:particle(particleId, pos, opts) end

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
---@param overlay? integer  -- if set, shows as overlay (action bar) for N ticks
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
