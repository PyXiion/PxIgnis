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
