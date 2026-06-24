---@meta

-- Entity wrapper. Metatable name: `"entity"`.
-- Player and Mob extend this; Hologram extends it.

---@class Entity
local Entity = {}

---Unique id.
---@type string
Entity.uuid = nil

---Entity type id, e.g. "minecraft:zombie".
---@type string
Entity.type = nil

---Default name (or player name for players).
---@type string
Entity.name = nil

---Display name if set, else name.
---@type string
Entity.displayName = nil

---Custom name. nil if unset.
---@type string|nil
Entity.customName = nil

---World this entity is currently in.
---@type World
Entity.world = nil

---Position. Read/write. Accepts a vec or `{x, y, z}` table on assignment.
---@type Vec
Entity.pos = nil

---Look direction (unit vector).
---@type Vec
Entity.dir = nil

---Body direction (horizontal-only, ignores pitch).
---@type Vec
Entity.bodyDir = nil

---Fall distance in blocks. Read/write.
---@type number
Entity.fallDistance = nil

---Fire ticks remaining. Read/write.
---@type integer
Entity.fireTicks = nil

---Whether the entity is glowing. Read/write.
---@type boolean
Entity.glowing = nil

---Whether the entity is invulnerable. Read/write.
---@type boolean
Entity.invulnerable = nil

---Whether the entity is sneaking. Read/write.
---@type boolean
Entity.isSneaking = nil

---Whether the entity is sprinting. Read/write.
---@type boolean
Entity.isSprinting = nil

---Remaining air ticks. Read/write.
---@type integer
Entity.air = nil

---Maximum air ticks.
---@type integer
Entity.maxAir = nil

---`true` after `entity:remove()` or chunk unload.
---@type boolean
Entity.removed = nil

---Current health. Read/write on LivingEntity; `nil` on others.
---@type number|nil
Entity.health = nil

---Max health (read/write on LivingEntity, `nil` on others).
---@type number|nil
Entity.maxHealth = nil

---Attribute getters/setters (LivingEntity only, `nil` on others):
---  speed, armor, armorToughness, attackDamage, attackSpeed,
---  knockbackResistance, luck, stepHeight, blockBreakSpeed,
---  gravity, scale, safeFallDistance, flyingSpeed
---@type number|nil
Entity.speed = nil
---@type number|nil
Entity.armor = nil
---@type number|nil
Entity.armorToughness = nil
---@type number|nil
Entity.attackDamage = nil
---@type number|nil
Entity.attackSpeed = nil
---@type number|nil
Entity.knockbackResistance = nil
---@type number|nil
Entity.luck = nil
---@type number|nil
Entity.stepHeight = nil
---@type number|nil
Entity.blockBreakSpeed = nil
---@type number|nil
Entity.gravity = nil
---@type number|nil
Entity.scale = nil
---@type number|nil
Entity.safeFallDistance = nil
---@type number|nil
Entity.flyingSpeed = nil

---Equipment slot accessors (LivingEntity only):
---  mainhand, offhand, head, chest, legs, feet
---@type Item|nil
Entity.mainhand = nil
---@type Item|nil
Entity.offhand = nil
---@type Item|nil
Entity.head = nil
---@type Item|nil
Entity.chest = nil
---@type Item|nil
Entity.legs = nil
---@type Item|nil
Entity.feet = nil

---Tag proxy: read/write command tags. Use `entity.tags["foo"] = true`.
---@type table<string, boolean>
Entity.tags = nil

---Deals damage to the entity.
---@param amount number
---@param source? Entity|Player   -- if omitted, generic damage is used
function Entity:damage(amount, source) end

---Raycasts from the entity's eye in look direction.
---@param range number
---@param includeFluids? boolean
---@param includeEntities? boolean
---@return RaycastResult|nil
function Entity:raycast(range, includeFluids, includeEntities) end

---Adds a status effect.
---@param effectId string   -- e.g. "minecraft:regeneration"
---@param duration integer  -- ticks
---@param amplifier? integer
---@param particles? boolean
---@param icon? boolean
---@return boolean  -- true if applied
function Entity:addEffect(effectId, duration, amplifier, particles, icon) end

---Removes a status effect.
---@param effectId string
---@return boolean
function Entity:removeEffect(effectId) end

---@param effectId string
---@return boolean
function Entity:hasEffect(effectId) end

---Sets the entity on fire for the given ticks.
---@param ticks integer
function Entity:setOnFireFor(ticks) end
