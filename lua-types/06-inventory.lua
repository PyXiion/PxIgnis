---@meta

-- Inventory wrapper. Metatable name: `"inventory"`.

---@class Inventory
local Inventory = {}

---Number of slots (multiple of 9, 9..54).
---@type integer
Inventory.size = nil

---@param slot integer  -- 1-based
---@return Item|nil
function Inventory:getItem(slot) end

---@param slot integer  -- 1-based
---@param item? Item    -- nil to clear
function Inventory:setItem(slot, item) end

---@param item? Item  -- nil to clear all
function Inventory:fill(item) end

function Inventory:clear() end

---Opens this inventory for a player.
---@param player Player
---@param title? string
---@return Container|nil
function Inventory:open(player, title) end

---Serialise to JSON.
---@return string
function Inventory:serialise() end
