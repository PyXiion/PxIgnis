---@meta

-- Mob (MobEntity) wrapper. Metatable name: `"mob"`. Inherits from Entity.

---@class Mob : Entity
local Mob = {}

---Always `true` for mobs. Useful for `target.type == "entity"` checks via duck-typing.
---@type true
Mob.isMob = nil

---@type boolean
Mob.aiActive = nil

---Current target. Read/write; assigning a table reads `.uuid` from it.
---@type Entity|Player|nil
Mob.target = nil

---Age in ticks.
---@type integer
Mob.age = nil

---Pathfinding progress 0..1, or 0 when idle.
---@type number
Mob.pathRemaining = nil

---@type boolean
Mob.pathFound = nil

---@param id string|fun(...)  -- behaviour id or function; nil to clear
function Mob:setAI(id) end

function Mob:clearAI() end

---Pathfinds to an entity or to coordinates.
---@param targetOrX Player|Entity|number
---@param y? number
---@param z? number
---@param speed? number
---@return boolean
function Mob:navigateTo(targetOrX, y, z, speed) end

function Mob:stopNavigation() end

---@param targetOrX Player|Entity|number
---@param y? number
---@param z? number
function Mob:lookAt(targetOrX, y, z) end

---@param pos Vec|Vec3Like
---@param speed? number
function Mob:moveToward(pos, speed) end

function Mob:jump() end

---@param target Entity
---@return boolean
function Mob:tryAttack(target) end

---@param target Entity
---@return boolean
function Mob:canSee(target) end

---@param target Entity|Vec|Vec3Like
---@return number
function Mob:distanceTo(target) end
