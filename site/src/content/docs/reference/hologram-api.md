---
title: Hologram
description: Floating text in the world, rendered as a TextDisplayEntity and visible to all nearby players.
---

Create floating, billboarded text in the world using the vanilla `minecraft:text_display` entity. Holograms are visible to all players in range of the entity — there is no per-player scope.

Metatable name: `"hologram"`

## Usage

### `world:spawnHologram(pos, text, opts?)`

Spawns a new hologram at `pos` with the given text. Returns a hologram wrapper.

```lua
local holo = world:spawnHologram({ x = 0, y = 64, z = 0 }, "Welcome to the server!")

holo.lines = { "Line 1", "Line 2", "Line 3" }

mc.schedule(40, function()
  holo:destroy()
end)
```

### Options

All options are optional. Pass any subset of the table below.

| Key | Type | Default | Description |
|---|---|---|---|
| `alignment` | `string` | `"center"` | `"left"`, `"center"`, or `"right"` |
| `billboard` | `string` | `"center"` | `"fixed"`, `"vertical"`, `"horizontal"`, or `"center"` |
| `lineWidth` | `number` | `200` | Max characters per line before wrap |
| `background` | `number` | `0x40000000` | ARGB int. Use `-1` for no background |
| `opacity` | `number` | `-1` | `0`..`255`. `-1` uses background alpha |
| `shadow` | `boolean` | `true` | Render a drop shadow |
| `seeThrough` | `boolean` | `false` | Render through walls |
| `glowing` | `boolean` | `false` | Entity outline glow |

```lua
local holo = world:spawnHologram({ x = 0, y = 64, z = 0 }, "Server info", {
  alignment = "center",
  billboard = "center",
  background = 0x80000000,
  shadow = true,
  seeThrough = true,
})
```

## Hologram Object

### `holo.text`

**type:** `string`

The full text rendered by the hologram, with newlines (`\n`) separating lines. Assign a string to replace all text at once.

### `holo.lines`

**type:** `table`

Lines array (strings). Read or assign; assigning replaces all text and re-joins with newlines.

### `holo.alignment`

**type:** `string`

One of `"left"`, `"center"`, `"right"`. Read/write.

### `holo.billboard`

**type:** `string`

One of `"fixed"`, `"vertical"`, `"horizontal"`, `"center"`. Read/write.

- `fixed` — does not rotate; text always faces the same direction
- `vertical` — rotates around the vertical axis only
- `horizontal` — rotates around the horizontal axis only
- `center` — always faces the camera (default)

### `holo.lineWidth`

**type:** `number`

Max characters per line before text wraps. Read/write.

### `holo.background`

**type:** `number`

ARGB integer (e.g. `0x40000000`). Set to `-1` for no background. Read/write.

### `holo.opacity`

**type:** `number`

`0`..`255`, or `-1` to use the background's alpha channel. Read/write.

### `holo.shadow`

**type:** `boolean`

Whether the text has a drop shadow. Read/write.

### `holo.seeThrough`

**type:** `boolean`

Whether the text renders through walls. Read/write.

### `holo.glowing`

**type:** `boolean`

Whether the entity has the glowing outline effect. Read/write.

### `holo:destroy()`

Destroys the hologram entity and removes it from the manager.

```lua
holo:destroy()
```

## Listing Holograms

### `mc.holograms()`

Returns a table of all live hologram wrappers (in creation order).

```lua
for _, holo in ipairs(mc.holograms()) do
  print(holo.uuid, holo.text)
end
```

### `mc.getHologram(uuid)`

Returns the hologram with the given UUID, or `nil` if not found.

```lua
local holo = mc.getHologram(some_uuid)
if holo then
  holo.text = "Updated"
end
```

## Lifetime

Holograms are global — they are visible to all players in range and persist until destroyed. All holograms are destroyed on `/ignis reload`. Per-player disconnect does not remove holograms (they are not owned by a player).

## Entity passthrough

Holograms are entities, so they expose the [entity properties](entity-api) via metatable delegation: `pos`, `uuid`, `world`, `removed`, `glowing`, `tags`, and so on. You can also call `entity:readNbt()` and `entity:writeNbt(nbt)` to inspect or modify the underlying NBT directly.
