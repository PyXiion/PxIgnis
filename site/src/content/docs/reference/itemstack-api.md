---
title: ItemStack
description: Lua wrapper for Minecraft item stacks — read properties, create items with components, safe reference handling.
---

The ItemStack wrapper provides read-only access to Minecraft item stacks. All wrappers
returned to Lua are **copies** — raw references never leak from the Lua boundary.

Metatable name: `"item"`

## Properties

### `item.id`

**type:** `string`

Item identifier (e.g. `"minecraft:diamond"`). Read-only.

### `item.count`

**type:** `number`

Stack size. Read-only.

### `item.custom_model_data`

**type:** `number` or `nil`

Custom model data integer. Read-only.

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
| `attackDamage` | `number` | Attack damage |

```lua
local sword = mc.createItem("diamond_sword", {
  count = 1,
  name = "&bLegendary Blade",
  lore = { "&7A mighty weapon", "", "&6+10 Attack Damage" },
  custom_model_data = 500,
  unbreakable = true,
  attackDamage = 10
})
```

## Safety

All wrappers returned to Lua are **copies**. This means:

- Lua scripts can never mutate the server's internal item stacks
- Modifications must go through API methods (`player:setItem()`, `inv:setItem()`)
- Multiple reads of the same slot return independent copies

```lua
local item = player.mainhand
item.count = 99  -- no effect, properties are read-only
player:setItem(player.selectedSlot, mc.createItem("diamond", 99))
```
