---@meta

-- ItemStack wrapper. Metatable name: `"item"`.

---@class Item
local Item = {}

---Item id, e.g. "minecraft:stone".
---@type string
Item.id = nil

---Stack size. Read/write.
---@type integer
Item.count = nil

---Custom name. nil if unset. Setting nil clears.
---@type string|nil
Item.name = nil

---Lore lines. nil if unset. Setting nil clears. Accepts a sequence of strings.
---@type string[]|nil
Item.lore = nil

---@type boolean
Item.unbreakable = nil

---First float of CustomModelData, or nil. Read/write.
---@type integer|nil
Item.custom_model_data = nil

---Returns a deep copy of the item.
---@return Item
function Item:copy() end

---Serialise this item to a JSON string.
---@return string
function Item:serialise() end
