---@meta

-- Container (open chest-like UI) wrapper. Metatable name: `"container"`.

---@class Container
local Container = {}

---The viewing player.
---@type Player
Container.player = nil

---The backing inventory.
---@type Inventory
Container.inventory = nil

function Container:close() end

---Sets or removes the click callback. Passing nil removes the callback and
---unlocks the inventory.
---@param callback? fun(player: Player, slot: integer, clickType: string, slotItem: Item|nil, cursorItem: Item|nil):boolean|nil
function Container:onClick(callback) end
