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

Registers a click handler. While a handler is registered, **every** click in the open menu goes through it and only
passes if the handler returns `true`.

- `callback` (`function` or `nil`) — Click handler, or `nil` to remove it (the menu then behaves like a normal chest)

Callback receives:

| Argument | Type | Description |
|---|---|---|
| `player` | [`Player`](/reference/player-api) | Player who clicked |
| `slot` | `number` or `nil` | Menu slot (1-based; GUI slots first, then the player's inventory), `nil` for a click outside the window |
| `clickType` | `string` | Click type (see below) |
| `slotItem` | [`ItemStack`](/reference/itemstack-api) or `nil` | Item in the clicked slot |
| `cursorItem` | [`ItemStack`](/reference/itemstack-api) or `nil` | Item on cursor |
| `inGui` | `boolean` | `true` if the slot belongs to the GUI inventory, `false` for the player's own slots and outside clicks |

Return `true` to let the click through. `nil`, `false` or an error in the callback cancel it (an error is also
logged). Shift-click, number keys, double-click, drag and drop are all clicks and are covered the same way.

`clickType` values: `"pickup"`, `"quick_move"`, `"swap"`, `"clone"`, `"throw"`, `"quick_craft"`, `"pickup_all"`.

```lua
container:onClick(function(player, slot, clickType, slotItem, cursorItem, inGui)
  if inGui then return false end   -- GUI slots are buttons
  return clickType ~= "quick_move"  -- the player's own inventory is free, but shift-click would fill the GUI
end)
container:onClick(nil)  -- remove the handler
```

PxIgnis uses **1-based** slot indexing in `inv:getItem`, `inv:setItem`, `container:onClick`
callbacks, and `chestgui:button`. The exception is `chestgui:set(row, col, item, callback)`
which uses **1-based** `(row, col)` for human-readable coordinates (rows 1–6, columns 1–9).
`inv:getItem` / `inv:setItem` with a slot outside `1..size` raise an error.
