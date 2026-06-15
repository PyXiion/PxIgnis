---
title: Structure
description: Load and place structure templates with mc.loadStructure and mc.loadStructureFile.
---

Load structure templates (`.nbt` files) and place them in the world with rotation, mirror, and entity callback support.

Structures are loaded from the server's `structures/` folder or from absolute file paths.

Metatable name: `"structure"`

## Loading Structures

### `mc.loadStructure(id)`

Loads a structure from the server's `structures/` folder.

- `id` (`string`) — Structure name (without `.nbt`)

Returns a structure wrapper or `nil`.

```lua
local struct = mc.loadStructure("my_house")
```

### `mc.loadStructureFile(path)`

Loads a structure from a file path.

- `path` (`string`) — Absolute or server-relative path

Returns a structure wrapper or `nil`.

```lua
local struct = mc.loadStructureFile("config/ignis/my_build.nbt")
```

## Properties

### `struct.size`

**type:** `table` (`{x, y, z}`)

Dimensions of the structure. Read-only.

## Methods

### `struct:place(world, pos, params?)`

Places the structure in the world.

- `world` ([`World`](/docs/reference/world-api)) — Target world
- `pos` (`table`) — `{x, y, z}` block position
- `params` (`table`, optional) — Placement parameters

`params` table:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `rotation` | `string` | `"none"` | `"none"` / `"0"`, `"clockwise_90"` / `"90"`, `"clockwise_180"` / `"180"`, `"counterclockwise_90"` / `"270"` |
| `mirror` | `string` | `"none"` | `"none"`, `"left_right"`, `"front_back"` |
| `on_entity` | `function` | `nil` | Callback per entity; return `false` to skip spawning |

Entity UUIDs are regenerated automatically — no duplicates.

```lua
struct:place(world, Vec(100, 64, 200), {
  rotation = "clockwise_90",
  mirror = "left_right",
  on_entity = function(entity)
    if entity:isMob() then return false end
  end
})
```
