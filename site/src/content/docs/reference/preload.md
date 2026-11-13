---
title: Preload scripts
description: Declare custom dimensions before the server creates its worlds.
---

Files named `*.preload.lua`, at any depth inside a [script root](/reference/configuration#script-roots), are
**preload scripts**. They are not normal scripts: the normal loader and the auto-reload watcher ignore them.

A preload script runs once, when the server starts, **before any world is created**, in its own Lua environment
(one per file). It has `preload`, `print`, the plain Lua standard library and `require` (modules from the script
roots). It has no `mc`, no events, no worlds and no players. Files run roots in order, then by sorted path.

`/ignis reload` does **not** run preload scripts again. If one was added, edited or removed since the server started,
the reload logs a warning: restart required.

The `preload` global exists only in preload files; the editor stubs declare it everywhere, but a normal script that
uses it gets "global 'preload' is not defined".

## `preload.dimension(id, options?)`

```lua
preload.dimension("planefall:rift", {
  generator = "void",               -- or { flat = { {"minecraft:stone", 1}, ... } }
  biome     = "minecraft:the_void",
  min_y = 0, height = 256,
  sky = {
    time          = "night",        -- "day" | "night"; nil = normal day cycle
    ambient_light = 0.1,
    skylight      = false,
    ceiling       = true,
  },
})
```

After the server starts the dimension is a normal world: `mc.world("planefall:rift")`, and
`player:teleport(x, y, z, "planefall:rift")`.

| Option                | Default                | Meaning |
|-----------------------|------------------------|---------|
| `generator`           | `"void"`               | `"void"` (empty) or `{ flat = { {block, thickness}, ... } }`: layers bottom-up from `min_y`, each a block id and a thickness ≥ 1; total thickness ≤ `height` |
| `biome`               | `"minecraft:the_void"` | Biome of the whole dimension. It must exist when the world loads (a missing biome fails the data pack) |
| `min_y`               | `0`                    | Lowest block, a multiple of 16 in -2032..2031 |
| `height`              | `256`                  | Build height, a multiple of 16 in 16..4064; `min_y + height` ≤ 2032 |
| `sky.time`            | `nil` (day cycle)      | `"day"` or `"night"`: the sky is stuck there. See below |
| `sky.ambient_light`   | `0.0`                  | Minimum light level, 0..1 |
| `sky.skylight`        | `true`                 | Whether the dimension has skylight |
| `sky.ceiling`         | `false`                | Whether it has a bedrock-style ceiling (affects light and mob spawning) |

With `time` set the dimension has no day cycle: the sky colors, sun, moon and stars are fixed to what the overworld
shows at noon (`"day"`) or midnight (`"night"`), and mobs follow it (monsters burn by day only). Without it the
dimension follows the overworld's day cycle.

Errors, all reported with the file name:

- the id is not namespaced (`ns:name`) or uses the `minecraft` namespace;
- `min_y`/`height` break the limits above; `ambient_light` is outside 0..1; a flat layer names an unknown block;
- `sky.time` is neither `"day"` nor `"night"`;
- an unknown option key (also inside `sky` and `generator`);
- the id is already declared: the message names both files.

A file with an error contributes **no** declarations; the error is logged (and sent to operators that have the
`px.ignis.prompt_errors` permission) and the server starts without them.

## How it works

The declarations become generated data packs, one per source file (script root + file), in
`config/pxignis/preload-packs/`; the folder is rewritten at each start, do not edit it. They are offered to the
server like the pause-menu pack, so they are enabled for a new world and added to the enabled packs of an existing
one at its next start. Do not disable them with `/datapack disable`: the dimension then vanishes at the next
restart.
