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

Entity methods inherited: `raycast()`, `addEffect()`, `removeEffect()`, `hasEffect()`, `setOnFireFor()`.
`damage()` is overridden for players (always uses generic damage source, no source parameter).

### `player:sendMessage(text)`

Sends a chat message.

- `text` (`string`) — Message text

```lua
player:sendMessage("Hello!")
```

### `player:sendActionBar(text)`

Sends an action bar message.

- `text` (`string` or `table`) — Message text, or a text component

A table is a vanilla text component, parsed by the game's own codec, so every vanilla field works. Common ones:
`text`, `color` (`"#RRGGBB"`, a colour name, or a number), `font` (`"namespace:id"`), `shadow_color` (ARGB number or
`"#AARRGGBB"`; `0` removes the shadow), `bold`, `italic`, `underlined`, `strikethrough`, `obfuscated`, and `extra`
(a list of components or strings appended after `text`). An invalid table raises an error with the game's message.

```lua
player:sendActionBar("&eHotbar message")
player:sendActionBar({
  text = "\u{E000}", font = "pxrp:hud", color = "#FFFFFF", shadow_color = 0,
  extra = { { text = " 50%", color = "gold", bold = true }, " left" },
})
```

### `player:sendTitle(title, subtitle?)`
### `player:sendTitle({title, subtitle?, fadeIn?, stay?, fadeOut?})`

Sends a title. Two forms:

**Positional form** — fixed fade timing (20/60/20 ticks):

| Param | Type | Description |
|---|---|---|
| `title` | `string` | Title text |
| `subtitle` | `string` | Subtitle text |

```lua
player:sendTitle("&cWarning", "&7Danger zone")
```

**Table form** — custom timing:

| Field | Type | Default | Description |
|---|---|---|---|
| `title` | `string` | `""` | Title text |
| `subtitle` | `string` | `""` | Subtitle text |
| `fadeIn` | `number` | `20` | Fade-in ticks |
| `stay` | `number` | `60` | Stay ticks |
| `fadeOut` | `number` | `20` | Fade-out ticks |

```lua
player:sendTitle({ title = "&aVictory!", subtitle = "&7You won!", fadeIn = 10, stay = 40 })
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

### `player:setCooldown(group, ticks)`
### `player:getCooldown(group)`
### `player:clearCooldown(group)`

Item use cooldowns (the grey overlay in the hotbar). `group` is an item id (`"minecraft:snowball"`) or the `group` of an
item's `use_cooldown`; every item that shares it is on cooldown. `getCooldown` returns the remaining **ticks** (0 when
not cooling down), `setCooldown(group, 0)` ends it like `clearCooldown`.

```lua
player:setCooldown("pxrp:flask", 60)
if player:getCooldown("pxrp:flask") > 0 then ... end
```

### `player:clear()`

Clears the inventory.

```lua
player:clear()
```



## Dialogs

Raw access to Minecraft 1.21.6+ dialogs. Most scripts want [`core:dialog`](/libraries/dialog), which builds the dialog,
routes the clicks and validates the input.

### `player:showDialog(spec)`

Shows a dialog.

- `spec` (`string` or `table`) — a registry dialog id such as `"minecraft:server_links"`, or a table in the vanilla
  [dialog JSON](https://minecraft.wiki/w/Dialog) shape (`type`, `title`, `body`, `inputs`, `actions`, `after_action`,
  `exit_action`, ...). An [ItemStack](/reference/itemstack-api) may stand wherever an item is expected. A spec the game
  rejects raises an error with the reason.

```lua
player:showDialog({
    type = "minecraft:notice",
    title = "Welcome",
    body = { { type = "minecraft:plain_message", contents = "Read the rules." } },
    action = { label = "OK", action = { type = "minecraft:dynamic/custom", id = "myplugin:ok" } },
})

mc.on("player.custom_click", function(e)
    if e.id == "myplugin:ok" then e.player:sendMessage("Thanks!") end
end)
```

A `custom` action sends its input values with the click in `e.payload`; routing data belongs in the action id.

### `player:closeDialog()`

Closes the dialog the player is looking at.

### `player:runCommand(cmd)`

Runs a command as the player, with their own permissions, position and rotation (`mc.execute(cmd, { as = player })`
keeps console permissions). Returns `true, result` or `false, message`.

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
