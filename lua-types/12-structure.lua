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

---@class StructureSaveOptions
---@field root? integer|string  -- script root: 1-based index or its path; default the first root

---Writes the structure as a gzipped vanilla `.nbt` file (current DataVersion) into a script root: the first one, or
---`opts.root`. `path` is relative to it, must end in `.nbt` and stay inside the root; parent folders are created and
---an existing file is overwritten. `mc.loadStructureFile(path)` reads it back.
---@param path string
---@param opts? StructureSaveOptions
function Structure:save(path, opts) end
