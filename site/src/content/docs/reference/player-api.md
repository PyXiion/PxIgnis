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
- `player.customName` (`string` or `nil`) — Assign to set.
- `player.isOp` (`boolean`) — Operator status. Read-only.
- `player.ping` (`number`) — Latency in ms. Read-only.

### Movement

- `player.isFlying` (`boolean`) — Read-only.
- `player.selectedSlot` (`number`) — Hotbar slot 0–8. Read-only.

### Health & Stats

- `player.food` (`number`) — Assign to set.
- `player.saturation` (`number`) — Read-only.
- `player.xpLevel` (`number`) — Read-only.
- `player.xpProgress` (`number`) — Progress to next level (0–1). Read-only.

### Other

- `player.gamemode` (`string`) — `"survival"`, `"creative"`, `"adventure"`, `"spectator"`. Assign to change.
- `player.data` (`table`) — Persistent per-player data. See [Storage](/reference/storage).

## Methods

Entity methods inherited: `damage()`, `raycast()`, `addEffect()`, `removeEffect()`, `hasEffect()`, `setOnFireFor()`, `readNbt()`, `writeNbt()`.

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

### `player:sendTitle(title, subtitle?, fadeIn?, stay?, fadeOut?)`

Sends a title.

| Param | Type | Default | Description |
|---|---|---|---|
| `title` | `string` | — | Title text |
| `subtitle` | `string` | — | Subtitle text |
| `fadeIn` | `number` | `10` | Fade-in ticks |
| `stay` | `number` | `70` | Stay ticks |
| `fadeOut` | `number` | `20` | Fade-out ticks |

```lua
player:sendTitle("&cWarning", "&7Danger zone", 10, 70, 20)
```

### `player:teleport(world, pos)`

Teleports to a position.

- `world` ([`World`](/reference/world-api)) — Target world
- `pos` (`table`) — `{x, y, z}` position

```lua
player:teleport(mc.world("minecraft:overworld"), { x = 0, y = 64, z = 0 })
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

### `player:give(item)`

Gives an item to the inventory.

- `item` ([`ItemStack`](/reference/itemstack-api)) — Item to give

```lua
player:give(mc.createItem("diamond", 1))
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

### `player:executeCommand(command)`

Executes a command as the player.

- `command` (`string`) — Command string (no `/` prefix)

```lua
player:executeCommand("say Hello!")
```

## Sidebar

Each player has a per-player sidebar using a local `Scoreboard` instance.

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

Current title. Assign to update (sends packet if visible).

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
