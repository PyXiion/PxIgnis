---
title: Structure
description: Load, capture, save and place structure templates.
---

Load structure templates (`.nbt` files) and place them in the world with rotation, mirror, and entity callback support.

Structures are loaded from the server's `structures/` folder or from absolute file paths.

Metatable name: `"structure"`

## Loading Structures

### `mc.loadStructure(id)`

Loads a structure from the server's `structures/` folder.

- `id` (`string`) — Structure name (without `.nbt`)

Returns a structure wrapper, or throws an error if not found.

```lua
local struct = mc.loadStructure("my_house")
```

### `mc.loadStructureFile(path)`

Loads a structure from a `.nbt` file inside one of the script roots (`config/ignis` by default).

- `path` (`string`) — Path relative to a script root. Paths that resolve outside the root they are found in
  (`..`, symlinks) are rejected.

Returns a structure wrapper, or throws an error if the file is missing, outside every script root or too large (32 MiB).

```lua
local struct = mc.loadStructureFile("my_build.nbt")
```

### `world:captureStructure(pos1, pos2, opts?)`

Captures the area between two corners (inclusive, any order) as a structure. Block entities are kept: chest contents,
structure blocks (also in `DATA` mode, so they work as markers) and so on.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `entities` | `boolean` | `false` | Also capture the entities in the area |
| `ignore` | `string` | none | Block id that is not saved, e.g. `"minecraft:structure_void"` |

Unknown options are errors. There is no 48-block limit like in the structure block, but the area may hold at most
**500 000 blocks** (so that `mc.loadStructureFile` can always read the file back); a larger area is an error. The
chunks of the area are loaded, which can take a while on the server thread for big areas.

Returns the same structure wrapper as `mc.loadStructure`.

```lua
local s = world:captureStructure(vec(0, 64, 0), vec(59, 73, 59), { entities = false, ignore = "minecraft:structure_void" })
s:save("structures/rooms/hall_2x2.nbt")
s:place(world, vec(100, 64, 200))
```

## Properties

### `struct.size`

**type:** `table` (`{x, y, z}`)

Dimensions of the structure. Read-only.

## Methods

### `struct:place(world, pos, params?)`

Places the structure in the world.

- `world` ([`World`](/reference/world-api)) — Target world
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
struct:place(world, vec(100, 64, 200), {
  rotation = "clockwise_90",
  mirror = "left_right",
  on_entity = function(entity)
    if entity:isMob() then return false end
  end
})
```

### `struct:save(path, opts?)`

Writes the structure as a gzipped vanilla structure file (`.nbt`, current data version) into a script root, readable
by `mc.loadStructureFile`, the structure block and `mc.loadStructure` (from a data pack).

- `path` (`string`) — relative to the **first** script root; must end in `.nbt` and must not leave the root (`..`,
  absolute paths and symlinks out of the root are refused). Missing folders are created, an existing file is
  overwritten.
- `opts.root` (`integer | string`) — another root: its 1-based index in `script_roots`, or its path.

```lua
s:save("structures/rooms/hall_2x2.nbt")
s:save("rooms/hall.nbt", { root = 2 })
```
