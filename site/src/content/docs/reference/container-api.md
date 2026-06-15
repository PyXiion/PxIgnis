---
title: Container
description: Lua wrapper for open container sessions — click callbacks, auto-locking, forced close.
---

A `Container` represents an open screen handler session. Returned by
[`inv:open(player, title?)`](/docs/inventory-api/#invopenplayer-title).

Metatable name: `"container"`

## Properties

### `container.player`

**type:** [`Player`](/docs/reference/player-api)

The player who opened the container. Read-only.

### `container.inventory`

**type:** [`Inventory`](/docs/reference/inventory-api)

The backing inventory. Read-only.

## Methods

### `container:close()`

Force-closes the container.

```lua
container:close()
```

### `container:onClick(callback)`

Registers a click callback and **auto-locks** the inventory.

- `callback` (`function` or `nil`) — Click handler, or `nil` to remove callback and unlock

Callback receives:

| Argument | Type | Description |
|---|---|---|
| `player` | [`Player`](/docs/reference/player-api) | Player who clicked |
| `slot` | `number` | Slot index (-1 = outside window) |
| `clickType` | `string` | Click type (see below) |
| `slotItem` | [`ItemStack`](/docs/reference/itemstack-api) or `nil` | Item in the clicked slot |
| `cursorItem` | [`ItemStack`](/docs/reference/itemstack-api) or `nil` | Item on cursor |

Return `false` from the callback to cancel the click.

`clickType` values: `"pickup"`, `"quick_move"`, `"swap"`, `"throw"`, `"quick_craft"`, `"pickup_all"`.

```lua
container:onClick(function(player, slot, clickType, slotItem, cursorItem)
  if slot == 0 then return false end
end)
container:onClick(nil)  -- remove callback and unlock
```

## Auto-Locking

When `onClick(fn)` is registered, the inventory is **automatically locked**:

- `removeStack()` returns empty (blocks hoppers/take)
- `clear()` is a no-op
- `setStack()` still works (Lua modifications bypass via `unlocked {}`)

Calling `onClick(nil)` unlocks the inventory, restoring normal item movement. This
prevents item theft when callbacks are active but fail to process (reload, crash).
