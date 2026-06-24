---@meta

-- Structure template wrapper. Metatable name: `"structure"`.

---@class Structure
local Structure = {}

---Dimensions of the structure.
---@type Vec
Structure.size = nil

---@param world World
---@param pos Vec|Vec3Like|{x:integer,y:integer,z:integer}
---@param params? StructurePlacementParams
---@return boolean
function Structure:place(world, pos, params) end
