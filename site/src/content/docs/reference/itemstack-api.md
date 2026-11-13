---
title: ItemStack
description: Lua wrapper for Minecraft item stacks — read properties, create items with components, safe reference handling.
---

Metatable name: `"item"`

## Properties

### `item.id`

**type:** `string`

Item identifier (e.g. `"minecraft:diamond"`). Read-only.

### `item.count`

**type:** `number`

Stack size.

### `item.name`

**type:** `string` or `nil`

Custom name. Assign or `nil` to remove.

### `item.lore`

**type:** `table`

Lore lines as an array of strings. Assign or `nil` to remove.

```lua
local item = mc.createItem("diamond")
item.lore = {
    "Cool diamond",
    "Very cool diamond"
}
```

### `item.unbreakable`

**type:** `boolean`

### `item.custom_model_data`

**type:** `number` or `nil`

Custom model data integer.

### `item:copy()`

Returns an independent copy of the item stack.

## Creating Items

### `mc.createItem(id, count)`

Creates an item stack with the given count.

- `id` (`string`) — Item identifier
- `count` (`number`) — Stack size

```lua
local diamonds = mc.createItem("diamond", 16)
```

### `mc.createItem(id, components)`

Creates an item stack with full component data.

- `id` (`string`) — Item identifier

`components` table:

| Component | Type | Description |
|---|---|---|
| `count` | `number` | Stack size |
| `name` | `string` | Custom name (`&` color codes) |
| `lore` | `table` | Array of lore lines |
| `custom_model_data` | `number` | Custom model data |
| `unbreakable` | `boolean` | Unbreakable flag |
| `data` | `table` | Custom data, see [Custom data](#custom-data) |
| `components` | `table` | Data components by id, see [Data components](#data-components) |
| `item_model`, `tooltip_style`, `rarity`, `item_name`, `glint`, `max_stack_size`, `use_cooldown`, `custom_model` | | The [shortcut properties](#shortcut-properties) |
Additional component keys may work depending on the item type (e.g., `potion_effects`, `firework`, `block_state`).
Keys that don't exist on the given item type are silently ignored.

```lua
local sword = mc.createItem("diamond_sword", {
  count = 1,
  name = "&bLegendary Blade",
  lore = { "&7A mighty weapon", "", "&6+10 Attack Damage" },
  custom_model_data = 500,
  unbreakable = true
})
```

## Data components

### `item:getComponent(id)`
### `item:setComponent(id, value)`
### `item:removeComponent(id)`
### `item:resetComponent(id)`

Any vanilla data component by id (the `minecraft:` prefix may be left out). The value goes through the component's own
codec and the same Lua/NBT conversion as [custom data](#custom-data): `"minecraft:rarity"` is `"epic"`,
`"minecraft:lore"` a list of strings, `"minecraft:unbreakable"` an empty table.

- `getComponent` returns `nil` when the stack has no such component.
- `setComponent` decodes the value; an unknown id or a value the codec rejects raises an error with the codec's
  message. An empty Lua table is accepted where the codec wants an empty list. `setComponent(id, nil)` is `removeComponent`.
- `removeComponent` is an explicit removal: the stack then lacks the component even if the item type has it by default
  (`max_damage` of a sword, say).
- `resetComponent` goes back to the item type's default: the component is present again if the item type has it.

```lua
local sword = mc.createItem("iron_sword", { components = { ["minecraft:max_damage"] = 5 } })
sword:removeComponent("minecraft:max_damage")   -- no durability bar at all
sword:resetComponent("minecraft:max_damage")    -- back to 250
```

## Shortcut properties

Read/write properties over the components above. Assigning `nil` resets to the item type's default, except
`use_cooldown` and `custom_model`, which are removed. The same keys work in `mc.createItem` and `items.define`.

| Property | Type | Component |
|---|---|---|
| `item.item_model` | `string` | `item_model` |
| `item.tooltip_style` | `string` | `tooltip_style` |
| `item.rarity` | `string` | `rarity` (`common`, `uncommon`, `rare`, `epic`) |
| `item.item_name` | `string` | `item_name` (plain text) |
| `item.glint` | `boolean` or `nil` | `enchantment_glint_override` |
| `item.max_stack_size` | `number` | `max_stack_size` (1-99) |
| `item.use_cooldown` | `{ seconds, group? }` or `nil` | `use_cooldown` |
| `item.custom_model` | `{ floats, flags, strings, colors }` or `nil` | `custom_model_data` (all four lists) |

`item.custom_model_data` stays the first float of `custom_model`. In `mc.createItem`, shortcuts are applied before
`components`, so a component wins when both set the same thing.

```lua
local flask = mc.createItem("splash_potion", {
  item_model = "pxrp:rain_flask",
  use_cooldown = { seconds = 3, group = "pxrp:flask" },
  custom_model = { strings = { "rain" }, colors = { 0x3366FF } },
})
```

## Custom data

### `item:getData(key)`
### `item:setData(key, value)`

Read and write the stack's vanilla `custom_data` component. `getData` returns `nil` for an absent key;
`setData(key, nil)` removes it.

Values are `boolean`, `string`, `number` and nested tables. Integral numbers are stored as int (long when large),
other numbers as double. A non-empty sequence becomes a list, any other table a compound with string keys (mixed or
non-string keys are an error). `nil` entries inside tables are dropped. Values come back as equal Lua values, booleans
stay booleans. Integers beyond 2^53 lose precision.

```lua
local key = mc.createItem("tripwire_hook", { name = "Cell key", data = { cell = 4, opens = { "a", "b" } } })
print(key:getData("cell"))        -- 4
key:setData("used", true)
key:setData("cell", nil)
```

Items returned by `player:getItem`, inventories, entity equipment and `e.item` are live references, so `setData`
changes the real stack. `item:copy()` is independent, and `setItem`/`give` store a copy of the item they are given.

## Safety

Use `item:copy()` to work with an independent copy.

```lua
local item = player.mainhand
item.count = 99  -- the amount changes real-time
local item2 = item:copy() -- A copy

player:setItem(player.selectedSlot, item2) -- Safe
player:setItem(player.selectedSlot, item)  -- Safe too (implicit copy)
player.offhand = item -- And this is safe too, yeah
```
