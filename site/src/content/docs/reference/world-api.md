---
title: World
description: Lua wrapper for Minecraft worlds — blocks, entities, particles, weather, and raycasting.
---

The world wrapper provides access to a Minecraft world. Obtain worlds via
`mc.world(name)` or from entity/player `.world` properties.

Metatable name: `"world"`

## Properties

### `world.name`

**type:** `string`

World identifier (e.g. `"overworld"`). Read-only.

### `world.time`

**type:** `number`

World time in ticks.

### `world.raining`

**type:** `boolean`

Whether it is raining.

### `world.thundering`

**type:** `boolean`

Whether it is thundering.

### `world.players`

**type:** sequence of [`Player`](/reference/player-api)

Array of online player wrappers in this world. Read-only.

### `world.regions`

**type:** sequence of [`Region`](/reference/region-api)

Array of all live region wrappers in this world. Read-only.

```lua
local world = mc.world("minecraft:overworld")
mc.broadcast("Time: " .. world.time)
world.time = 1000
```

## Block Methods

### `world:setBlock(pos, blockId)`

Sets a block at the given position.

- `pos` (`table`) — `{x, y, z}` block position
- `blockId` (`string`) — Block identifier. `minecraft:` prefix optional

```lua
world:setBlock({ x = 0, y = 64, z = 0 }, "diamond_block")
world:setBlock({ x = 0, y = 65, z = 0 }, "minecraft:torch")
```

### `world:getBlock(pos)`

Returns the block ID at the given position.

- `pos` (`table`) — `{x, y, z}` block position

```lua
local block = world:getBlock({ x = 0, y = 64, z = 0 })
```

### `world:fill(pos1, pos2, blockId)`

Fills a cuboid between two positions with a block (max 32,768 blocks).

- `pos1` (`table`) — First corner `{x, y, z}`
- `pos2` (`table`) — Opposite corner `{x, y, z}`
- `blockId` (`string`) — Block identifier

```lua
world:fill({ x = -10, y = 60, z = -10 }, { x = 10, y = 70, z = 10 }, "stone")
```

## Entity Methods

### `world:spawn(entityId, pos, overrides?)`

Spawns an entity at the given position.

- `entityId` (`string`) — Entity type (e.g. `"pig"`, `"zombie"`)
- `pos` (`table`) — `{x, y, z}` spawn position
- `overrides` (`table`, optional) — Override keys: `health` (number) and `custom_name` (string). Arbitrary NBT is not supported.

Returns the spawned entity wrapper, or `nil` on failure.

```lua
local pig = world:spawn("pig", { x = 10, y = 64, z = 10 })
local zombie = world:spawn("zombie", { x = 0, y = 64, z = 0 }, {
  CustomName = "{\"text\":\"Bob\"}",
  Health = 40.0
})
```

### `world:getEntities(pos, radius, typeFilter?)`

Gets entities within a radius of a position.

- `pos` (`table`) — Center `{x, y, z}`
- `radius` (`number`) — Search radius in blocks
- `typeFilter` (`string`, optional) — Entity type to filter by (e.g. `"minecraft:pig"`)

```lua
local all = world:getEntities({ x = 0, y = 64, z = 0 }, 10)
local pigs = world:getEntities({ x = 0, y = 64, z = 0 }, 10, "minecraft:pig")
```

## Effects

### `world:particle(id, pos, opts?)`

Spawns particles at a position.

- `id` (`string`) — Particle identifier
- `pos` (`table`) — `{x, y, z}` position

Optional `opts` table:

| Option | Type | Default | Description |
|---|---|---|---|
| `count` | `number` | `1` | Number of particles |
| `spread` | `table` | `{0, 0, 0}` | Spread vector (also accepts `delta` as alias) |
| `speed` | `number` | `0` | Particle speed |

```lua
world:particle("minecraft:flame", { x = 0, y = 65, z = 0 })
world:particle("minecraft:heart", { x = 0, y = 65, z = 0 }, {
  count = 5,
  delta = { 0.5, 0.5, 0.5 },
  speed = 0.1
})
```

### `world:playSound(id, x, y, z, volume?, pitch?)`

Plays a sound at a position for all players in the world.

- `id` (`string`) — Sound identifier
- `x`, `y`, `z` (`number`) — Position
- `volume` (`number`, default `1.0`) — Volume
- `pitch` (`number`, default `1.0`) — Pitch

```lua
world:playSound("minecraft:entity.experience_orb.pickup", 0, 64, 0)
world:playSound("minecraft:entity.ender_dragon.growl", 0, 64, 0, 2.0, 0.5)
```

## Holograms

### `world:spawnHologram(pos, text, opts?)`

Spawns a hologram at the given position.

- `pos` (`table`) — `{x, y, z}` spawn position
- `text` (`string`) — Display text
- `opts` (`table`, optional) — Hologram options

`opts` table:

| Option | Type | Default | Description |
|---|---|---|---|
| `alignment` | `string` | `"center"` | Text alignment |
| `billboard` | `string` | `"center"` | Billboard mode (`"fixed"`, `"vertical"`, `"horizontal"`, `"center"`) |
| `lineWidth` | `number` | `200` | Line width |
| `background` | `number` | `0x40000000` | ARGB background color |
| `opacity` | `number` | `255` | Text opacity (0–255) |
| `shadow` | `boolean` | `false` | Text shadow |
| `seeThrough` | `boolean` | `false` | See-through |
| `glowing` | `boolean` | `false` | Glowing effect |

Returns a [Hologram](/reference/hologram-api).

```lua
local holo = world:spawnHologram(vec(0, 70, 0), "&6Welcome!", {
    billboard = "center",
    background = 0x80000000
})
```

## Broadcasting

### `world:broadcastInRange(text, x, y, z, range, overlay?)`

Broadcasts a message to players within range of a position.

- `text` (`string`) — Message text
- `x`, `y`, `z` (`number`) — Origin position
- `range` (`number`) — Radius in blocks
- `overlay` (`number`, optional) — If provided, sends as title overlay with the given duration in ticks

```lua
world:broadcastInRange("&cDanger nearby!", 0, 64, 0, 20)
```

## Regions

### `world:createRegion(posA, posB)`

Creates a new spatial region spanning between two corners. The corners are auto-normalized — order does not matter.

- `posA` (`table`) — First corner `{x, y, z}`
- `posB` (`table`) — Second corner `{x, y, z}`

Returns a [`Region`](/reference/region-api) wrapper.

```lua
local r = world:createRegion({ x = 0, y = 0, z = 0 }, { x = 100, y = 64, z = 100 })
```

See the [Region](/reference/region-api) page for events and methods.

### `world:getRegion(id)`

Returns the [`Region`](/reference/region-api) wrapper for the given ID, or `nil` if no region with that ID exists in this world.

```lua
local r = world:getRegion(42)
if r then r:destroy() end
```

### `world:getRegionsAt(pos)`

Returns a sequence of all [`Region`](/reference/region-api) wrappers in this world that contain the given position. Returns `{}` if none. Regions may overlap, so multiple results are possible.

```lua
for _, r in ipairs(world:getRegionsAt({ x = 50, y = 64, z = 50 })) do
  print("Inside", r.id)
end
```

See [Region lookup](/reference/region-api#lookup) for the global `mc.getRegion` variant and details.

## Raycasting

### `world:raycast(startVec, dirVec, range, includeFluids?, includeEntities?)`

Performs a raycast and returns a hit result or `nil`.

- `startVec` (`table`) — `{x, y, z}` origin
- `dirVec` (`table`) — `{x, y, z}` direction
- `range` (`number`) — Max distance
- `includeFluids` (`boolean`, optional) — Include fluid blocks
- `includeEntities` (`boolean`, optional) — Include entity hits

Returns a hit result table or `nil`:

For **entity hits**:
- `hit.type` — `"entity"`
- `hit.entity` — Hit entity wrapper
- `hit.hit` — Intersection point `{x, y, z}`

For **block hits**:
- `hit.type` — `"block"`
- `hit.blockPos` — Block position `{x, y, z}`
- `hit.hit` — Intersection point `{x, y, z}`
- `hit.side` — Block face (e.g. `"north"`)
- `hit.normal` — Face normal `{x, y, z}`

```lua
local hit = world:raycast({ x = 0, y = 64, z = 0 }, { x = 0, y = -1, z = 0 }, 10)
if hit then
  mc.broadcast("Hit at " .. hit.hit.x .. ", " .. hit.hit.y .. ", " .. hit.hit.z)
end
```
