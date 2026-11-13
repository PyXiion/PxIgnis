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

---@class TextComponent
---@field text? string
---@field color? string|integer         -- "#RRGGBB", a colour name, or a number 0xRRGGBB
---@field font? string                  -- "namespace:id" of a font
---@field shadow_color? string|integer  -- ARGB number or "#AARRGGBB"; 0 = no shadow
---@field bold? boolean
---@field italic? boolean
---@field underlined? boolean
---@field strikethrough? boolean
---@field obfuscated? boolean
---@field extra? (TextComponent|string)[]
---Any other field of the vanilla text component format (`translate`, `object`, `hover_event`, ...) is accepted too.

---Sends an action bar message: a string, or a text component table (see TextComponent).
---@param text string|TextComponent
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

---Starts a cooldown on an item id (`"minecraft:snowball"`) or a `use_cooldown` group (`"pxrp:rod"`); every item of that
---group is on cooldown. `ticks` 0 ends it.
---@param group string
---@param ticks integer
function Player:setCooldown(group, ticks) end

---Remaining cooldown of an item id or `use_cooldown` group, in ticks; 0 when it is not cooling down.
---@param group string
---@return integer
function Player:getCooldown(group) end

---Ends the cooldown of an item id or group.
---@param group string
function Player:clearCooldown(group) end

---Sets an inventory slot directly. Slot index 0-based; nil to clear.
---@param slot integer
---@param item? Item
function Player:setItem(slot, item) end

---@param slot integer
---@return Item|nil
function Player:getItem(slot) end

---Clears the player's inventory.
function Player:clear() end

---Shows a dialog (Minecraft 1.21.6+). `spec` is a registry dialog id (`"minecraft:server_links"`) or a table in the
---vanilla dialog JSON shape (`type`, `title`, `body`, `inputs`, `actions`, `after_action`, `exit_action`, ...); an Item
---is accepted wherever the JSON wants an item stack. A spec the game rejects raises an error with the codec's message.
---Clicks on `custom` actions arrive as `player.custom_click`. `require "core:dialog"` is the convenient layer.
---@param spec string|table
function Player:showDialog(spec) end

---Closes the dialog the player is looking at.
function Player:closeDialog() end

---Runs a command as this player: their own permissions, position and rotation (unlike `mc.execute`, which runs with
---console permissions). Returns `true, result` or `false, message`, like `mc.execute`.
---@param command string  -- without the leading slash
---@return boolean, integer|string
function Player:runCommand(command) end
