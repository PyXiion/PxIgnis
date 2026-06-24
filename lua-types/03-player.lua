---@meta

-- Player wrapper. Metatable name: `"player"`. Inherits from Entity.

---@class Player : Entity
local Player = {}

---@type integer
Player.food = nil

---@type number
Player.saturation = nil

---@type Gamemode
Player.gamemode = nil

---Latency in ms.
---@type integer
Player.ping = nil

---@type integer
Player.xpLevel = nil

---@type number
Player.xpProgress = nil

---@type boolean
Player.isOp = nil

---@type integer
Player.selectedSlot = nil

---@type boolean
Player.isFlying = nil

---Sidebar object. Read returns the live Sidebar wrapper or nil. Write accepts
---a `SidebarConfig` to create/update, or `nil` to destroy.
---@type Sidebar|SidebarConfig|nil
Player.sidebar = nil

---Per-player persistent storage. Auto-saved to JSON.
---@type table<string, any>
Player.data = nil

---@param permission string  -- e.g. "px.example.use"
---@return boolean
function Player:hasPermission(permission) end

---Sends a chat message.
---@param text string
function Player:sendMessage(text) end

---Sends an action bar message.
---@param text string
function Player:sendActionBar(text) end

---Sends a title. Two forms:
---  sendTitle(title, subtitle?)                     -- default fade timings
---  sendTitle({ title, subtitle?, fadeIn?, stay?, fadeOut? })
---@param titleOrTable string|table
---@param subtitle? string
function Player:sendTitle(titleOrTable, subtitle) end

---@param reason? string
function Player:kick(reason) end

---Teleports the player. Pass world name to cross-dimension teleport.
---@param x number
---@param y number
---@param z number
---@param worldName? string
function Player:teleport(x, y, z, worldName) end

---@param amount number
function Player:damage(amount) end

---@param amount number
function Player:heal(amount) end

---Plays a sound for just this player.
---@param soundId string   -- e.g. "minecraft:entity.experience_orb.pickup"
---@param volume? number
---@param pitch? number
function Player:playSound(soundId, volume, pitch) end

---Gives the player an item. Accepts an item id string with optional count, or
---an Item from `mc.createItem`.
---@param itemOrId string|Item
---@param count? integer
function Player:give(itemOrId, count) end

---Sets an inventory slot directly. Slot index 0-based; nil to clear.
---@param slot integer
---@param item? Item
function Player:setItem(slot, item) end

---@param slot integer
---@return Item|nil
function Player:getItem(slot) end

---Clears the player's inventory.
function Player:clear() end
