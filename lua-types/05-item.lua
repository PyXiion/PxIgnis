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

---Reads a value from the item's custom data (vanilla `custom_data` component). Booleans, strings, numbers and nested
---tables come back as the Lua values that were stored; nil if the key is absent.
---@param key string
---@return any
function Item:getData(key) end

---Writes a value into the item's custom data. `nil` removes the key. Integral numbers are stored as int/long, other
---numbers as double; a sequence becomes a list, any other table a compound with string keys.
---Items from `player:getItem`, inventories, equipment and `e.item` are live references: the change applies to the
---real stack.
---@param key string
---@param value any
function Item:setData(key, value) end

---Value of any data component by id, converted through the component's codec (`"minecraft:rarity"` -> "epic",
---`"minecraft:lore"` -> list of strings, `"minecraft:unbreakable"` -> {}). nil if the stack has none.
---Raises an error for an unknown id.
---@param id string
---@return any
function Item:getComponent(id) end

---Sets a data component from a Lua value, decoded by the component's codec; a codec failure is raised with the
---codec's message. `nil` is the same as removeComponent.
---@param id string
---@param value any
function Item:setComponent(id, value) end

---Explicit removal: the stack has no such component, even one the item type has by default (e.g. max_damage).
---@param id string
function Item:removeComponent(id) end

---Back to the item type's default: the component is present again if the item type has it.
---@param id string
function Item:resetComponent(id) end

---Item model id (`item_model` component), e.g. "minecraft:diamond". Setting nil resets to the item type's default.
---@type string|nil
Item.item_model = nil

---Tooltip style id (resource pack tooltip sprites). Setting nil resets.
---@type string|nil
Item.tooltip_style = nil

---"common" | "uncommon" | "rare" | "epic". Setting nil resets.
---@type string
Item.rarity = nil

---Item name (`item_name` component: the base name, unlike `name`, it is not italic and anvil renaming overrides it).
---Plain text. Setting nil resets.
---@type string|nil
Item.item_name = nil

---`enchantment_glint_override`: true/false forces the glint on/off, nil resets it.
---@type boolean|nil
Item.glint = nil

---Maximum stack size, 1..99. Setting nil resets.
---@type integer
Item.max_stack_size = nil

---Use cooldown: `seconds` and an optional `group` id (items of a group share the cooldown, see
---`player:setCooldown`). Setting nil removes it.
---@type {seconds: number, group?: string}|nil
Item.use_cooldown = nil

---All four lists of the `custom_model_data` component. Missing lists are empty; setting nil removes the component.
---`custom_model_data` is only the first float.
---@type {floats: number[], flags: boolean[], strings: string[], colors: integer[]}|nil
Item.custom_model = nil

---Returns a deep copy of the item.
---@return Item
function Item:copy() end

---Serialise this item to a JSON string.
---@return string
function Item:serialise() end
