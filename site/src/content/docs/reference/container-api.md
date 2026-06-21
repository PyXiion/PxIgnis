---
title: Container
description: Lua wrapper for open container sessions — click callbacks, auto-locking.
---

A `Container` represents an open screen handler session. Returned by
[`inv:open(player, title?)`](/docs/inventory-api/#invopenplayer-title).

Metatable name: `"container"`

## Properties

### `container.player`

**type:** [`Player`](/reference/player-api)

The player who opened the container. Read-only.

### `container.inventory`

**type:** [`Inventory`](/reference/inventory-api)

The backing inventory. Read-only.

## Methods

### `container:close()`

Closes the container.

```lua
container:close()
```

### `container:onClick(callback)`

Registers a click callback and **auto-locks** the inventory.

- `callback` (`function` or `nil`) — Click handler, or `nil` to remove callback and unlock

Callback receives:

| Argument | Type | Description |
|---|---|---|
| `player` | [`Player`](/reference/player-api) | Player who clicked |
| `slot` | `number` | Slot index (-1 = outside window) |
| `clickType` | `string` | Click type (see below) |
| `slotItem` | [`ItemStack`](/reference/itemstack-api) or `nil` | Item in the clicked slot |
| `cursorItem` | [`ItemStack`](/reference/itemstack-api) or `nil` | Item on cursor |

Return `false` from the callback to cancel the click.

`clickType` values: `"pickup"`, `"quick_move"`, `"swap"`, `"throw"`, `"quick_craft"`, `"pickup_all"`.

```lua
container:onClick(function(player, slot, clickType, slotItem, cursorItem)
  if slot == 0 then return false end
end)
container:onClick(nil)  -- remove callback and unlock
```

PxIgnis uses **1-based** slot indexing in `inv:getItem`, `inv:setItem`, `container:onClick`
callbacks, and `chestgui:button`. The exception is `chestgui:set(row, col, item, callback)`
which uses **1-based** `(row, col)` for human-readable coordinates (rows 1–6, columns 1–9).

When `onClick(fn)` is registered, the inventory is **automatically locked** to prevent
item theft. Calling `onClick(nil)` unlocks it.
