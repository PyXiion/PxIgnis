---@meta

-- Result of `entity:raycast()` and `world:raycast()`.

---@class RaycastHitBlock
---@field type '"block"'
---@field blockPos Vec
---@field hit Vec
---@field side string       -- e.g. "north", "south", "up", "down"
---@field normal Vec

---@class RaycastHitEntity
---@field type '"entity"'
---@field entity Entity
---@field hit Vec

---@alias RaycastResult RaycastHitBlock | RaycastHitEntity
