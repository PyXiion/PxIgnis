---
title: Inventory
description: Lua wrapper for modifiable inventories — create, modify slots, open for players.
---

The inventory API lets you create and manage virtual inventories. Inventories can be
opened for players, returning a [Container](/reference/container-api) session.

Metatable name: `"inventory"`

## Creating Inventories

### `mc.createInventory(size)`

Creates a new inventory. Size must be a multiple of 9 (9–54).

- `size` (`number`) — Number of slots

```lua
local inv = mc.createInventory(27)
```

## Properties

### `inv.size`

**type:** `number`

Number of slots. Read-only.

## Methods

### `inv:getItem(slot)`

Returns the item in the given slot, or `nil` if empty.

- `slot` (`number`) — Slot index (1‑based)

```lua
local item = inv:getItem(1)
```

### `inv:setItem(slot, item)`

Sets a slot to the given item stack.

- `slot` (`number`) — Slot index (1‑based)
- `item` ([`ItemStack`](/reference/itemstack-api) or `nil`) — Item to place, or `nil` to clear

```lua
inv:setItem(1, mc.createItem("diamond", 1))
inv:setItem(2, nil)
```

### `inv:fill(item)`

Fills every slot with the given item (replaces existing contents). Pass `nil` to clear all slots.

- `item` ([`ItemStack`](/reference/itemstack-api) or `nil`) - Item to fill with, or `nil` to clear

```lua
inv:fill(mc.createItem("stone", 1))
```

### `inv:clear()`

Clears all slots.

```lua
inv:clear()
```

### `inv:open(player, title?)`

Opens the inventory for a player. Returns a [Container](/reference/container-api) session.

- `player` ([`Player`](/reference/player-api)) — Target player
- `title` (`string`, optional) — Window title

The returned container tracks the open session with click handling, forced close,
and access to the backing inventory and player.

```lua
local container = inv:open(player, "&6My Chest")
container:onClick(function(p, slot, clickType, slotItem, cursorItem)
  if slot == 1 then return false end
end)
```
