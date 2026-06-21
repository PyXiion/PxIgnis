---
title: Player
description: Lua wrapper for Minecraft players — properties, inventory, effects, sidebar, and more.
---

The player wrapper provides access to a connected player. Players are obtained via
`mc.players`, `world.players`, or as event handler arguments.

Player extends [Entity](/reference/entity-api) — all entity properties and methods are available on players.
Only player-specific additions are listed here.

Metatable name: `"player"`

## Properties

### Identity

- `player.name` (`string`) — Username. Read-only.
- `player.uuid` (`string`) — UUID. Read-only.
- `player.displayName` (`string`) — Read-only.
- `player.customName` (`string` or `nil`)
- `player.isOp` (`boolean`) — Operator status. Read-only.
- `player.ping` (`number`) — Latency in ms. Read-only.

### Movement

- `player.isFlying` (`boolean`) — Read-only.
- `player.selectedSlot` (`number`) — Hotbar slot 0–8. Read-only.

### Health & Stats

- `player.food` (`number`)
- `player.saturation` (`number`) — Read-only.
- `player.xpLevel` (`number`) — Read-only.
- `player.xpProgress` (`number`) — Progress to next level (0–1). Read-only.

### Other

- `player.gamemode` (`string`) — `"survival"`, `"creative"`, `"adventure"`, `"spectator"`.
- `player.data` (`table`) — Persistent per-player data. See [Storage](/reference/storage).

## Methods

Entity methods inherited: `raycast()`, `addEffect()`, `removeEffect()`, `hasEffect()`, `setOnFireFor()`, `readNbt()`, `writeNbt()`.
`damage()` is overridden for players (always uses generic damage source, no source parameter).

### `player:sendMessage(text)`

Sends a chat message.

- `text` (`string`) — Message text

```lua
player:sendMessage("Hello!")
```

### `player:sendActionBar(text)`

Sends an action bar message.

- `text` (`string`) — Message text

```lua
player:sendActionBar("&eHotbar message")
```

### `player:sendTitle(title, subtitle?)`

Sends a title.

| Param | Type | Description |
|---|---|---|
| `title` | `string` | Title text |
| `subtitle` | `string` | Subtitle text |

Fade timing is fixed at 20/60/20 ticks (in/stay/out).

```lua
player:sendTitle("&cWarning", "&7Danger zone")
```

### `player:teleport(x, y, z, world?)`

Teleports to coordinates.

- `x`, `y`, `z` (`number`) — Target coordinates
- `world` (`string`, optional) — Target world name (e.g. `"minecraft:overworld"`)

```lua
player:teleport(0, 64, 0)
player:teleport(0, 64, 0, "minecraft:the_nether")
```

### `player:heal(amount)`

Restores health.

- `amount` (`number`) — Health to restore

```lua
player:heal(20)
```

### `player:kick(reason)`

Kicks the player.

- `reason` (`string`) — Kick message

```lua
player:kick("You have been kicked!")
```

### `player:hasPermission(node)`

Checks a permission node.

- `node` (`string`) — Permission node

```lua
if player:hasPermission("myplugin.admin") then end
```

### `player:playSound(id, volume?, pitch?)`

Plays a sound.

- `id` (`string`) — Sound identifier
- `volume` (`number`, default `1.0`) — Volume
- `pitch` (`number`, default `1.0`) — Pitch

```lua
player:playSound("minecraft:entity.ender_dragon.growl", 1.0, 1.0)
```

### `player:give(item[, count])`

Gives an item to the inventory. Accepts an ItemStack wrapper or an item ID string.

- `item` ([`ItemStack`](/reference/itemstack-api) or `string`) — Item to give, or item ID
- `count` (`number`, optional) — Stack size (only with string ID)

```lua
player:give(mc.createItem("diamond", 1))
player:give("diamond", 16)
```

### `player:setItem(slot, item)`

Sets an item in a slot.

- `slot` (`number`) — Slot index
- `item` ([`ItemStack`](/reference/itemstack-api) or `nil`) — Item or `nil` to clear

### `player:getItem(slot)`

Gets the item in a slot.

- `slot` (`number`) — Slot index

### `player:clear()`

Clears the inventory.

```lua
player:clear()
```



## Sidebar

Each player has a per-player sidebar.

### `player.sidebar = {...}`

Creates or updates the sidebar. Auto-shows on first creation. Partial updates merge.

```lua
player.sidebar = {
  title = "&6My Server",
  lines = { "&aWelcome!", "", "&7Players: " .. mc.onlineCount }
}
player.sidebar = { title = "&6Updated Title" }
player.sidebar = { lines = { "Line 1", "Line 2" } }
player.sidebar = { visible = false }
player.sidebar = nil  -- destroy
```

### `sb.title`

**type:** `string`

Current title. Assign to update.

### `sb.lines`

**type:** `table`

Lines array (strings). Assign to replace all lines.

### `sb.visible`

**type:** `boolean`

Whether shown. Read-only.

### `sb.lineCount`

**type:** `number`

Number of lines. Read-only.

### `sb:setLine(n, text)`

Sets a specific line (1‑indexed).

- `n` (`number`) — Line index
- `text` (`string`) — Line text

### `sb:show()`

Shows the sidebar.

### `sb:hide()`

Hides the sidebar.

### `sb:destroy()`

Destroys the sidebar permanently.

```lua
local sb = player.sidebar
sb.title = "Updated Title"
sb.lines = { "Line A", "Line B", "Line C" }
sb:setLine(2, "Modified Line B")
sb:hide() sb:show() sb:destroy()
```
