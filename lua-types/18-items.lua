---@meta

-- Items library — custom item templates with scripted callbacks.
-- Access via `require "items"`.

---@class ItemTemplate
---@field key? string
---@field id string
---@field modelData? integer
---@field name? string
---@field lore? string[]
---@field unbreakable? boolean
---@field onUse? fun(player: Player, hand: string, item: Item, template: ItemTemplate): (boolean|nil)
---@field onAttack? fun(player: Player, target: Entity, item: Item, template: ItemTemplate): (boolean|nil)
---@field onConsume? fun(player: Player, item: Item, template: ItemTemplate): (boolean|nil)
---@field onPickup? fun(player: Player, item: Item, count: integer, template: ItemTemplate): (boolean|nil)
---@field onInteractEntity? fun(player: Player, entity: Entity, hand: string, item: Item, template: ItemTemplate): (boolean|nil)
---@field onTick? fun(player: Player, item: Item, hand: "main"|"off", template: ItemTemplate): nil
---@field onInventoryTick? fun(player: Player, item: Item, slot: integer, template: ItemTemplate): nil
local ItemTemplate = {}

---Creates an item stack with this template's properties.
---@param count? integer
---@return Item
function ItemTemplate:make(count) end

---Checks if an ItemStack matches this template by id + modelData.
---@param item Item
---@return boolean
function ItemTemplate:matches(item) end

---Returns true if the player has any matching item in their inventory.
---@param player Player
---@return boolean
function ItemTemplate:has(player) end

---@class ItemsLib
local items = {}

---Defines a new item template. Returns the template object.
---@param opts ItemTemplate
---@return ItemTemplate
function items.define(opts) end

---Returns true if the item matches the template.
---@param item Item
---@param template ItemTemplate
---@return boolean
function items.is(item, template) end

---Returns true if the player has any matching item in their inventory.
---@param player Player
---@param template ItemTemplate
---@return boolean
function items.has(player, template) end

---Returns the matching template for the item, or nil.
---@param item Item
---@return ItemTemplate|nil
function items.find(item) end

