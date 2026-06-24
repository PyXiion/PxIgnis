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

### `world:getBlockState(pos)`

Returns the block state at a position as `{ id, properties }`, or `nil` if air.

- `pos` (`table`) — `{x, y, z}` block position

```lua
local state = world:getBlockState({ x = 0, y = 64, z = 0 })
if state then
  print(state.id)                 -- "minecraft:grass_block"
  for k, v in pairs(state.properties) do
    print(k, v)                   -- "snowy", false
  end
end
```

### `world:setBlockState(pos, state)`

Sets a block with property overrides.

- `pos` (`table`) — `{x, y, z}` block position
- `state` (`table`) — `{ id, properties? }`

```lua
world:setBlockState({ x = 0, y = 64, z = 0 }, {
  id = "minecraft:command_block",
  properties = { conditional = true }
})
```

## Entity Methods

### `world:spawn(entityId, pos, overrides?)`

Spawns an entity at the given position.

- `entityId` (`string`) — Entity type (e.g. `"pig"`, `"zombie"`)
- `pos` (`table`) — `{x, y, z}` spawn position
- `overrides` (`table`, optional) — Override keys: `health` (number) and `custom_name` (string). Arbitrary NBT is not
  supported.

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


### `world:getEntitiesBySelector(selector, opts?)`

Queries entities using Minecraft's target selector syntax. Supports `@a`, `@e`, `@p`, `@r`, `@s` and all selector arguments (`type=`, `distance=`, `tag=`, `nbt=`, etc.).

- `selector` (`string`) — Target selector (e.g. `"@e[type=pig,distance=..10]"`)
- `opts` (`table`, optional) — `{ as = entity, at = pos }` to set context entity/position

```lua
local pigs = world:getEntitiesBySelector("@e[type=minecraft:pig,distance=..10]")
local nearby = world:getEntitiesBySelector("@a", { at = pos })
```

## Effects

### `world:particle(id, pos, opts?)`

Spawns particles at a position.

- `id` (`string`) — Particle identifier
- `pos` (`table`) — `{x, y, z}` position

Optional `opts` table:

| Option   | Type     | Default     | Description                                                            |
|----------|----------|-------------|------------------------------------------------------------------------|
| `count`  | `number` | `1`         | Number of particles                                                    |
| `spread` | `table`  | `{0, 0, 0}` | Spread vector (also accepts `delta` as alias)                          |
| `speed`  | `number` | `0`         | Particle speed                                                         |
| `data`   | `table`  | —           | Particle-specific NBT fields (required for particles with data codecs) |

The `data` table accepts per-particle fields — the following types are supported:

| Particle id             | `data` fields                                                                     |
|-------------------------|-----------------------------------------------------------------------------------|
| `block`                 | `block` (string)                                                                  |
| `block_marker`          | `block` (string)                                                                  |
| `block_crumble`         | `block` (string)                                                                  |
| `dragon_breath`         | `power` (number, default `1.0`)                                                   |
| `dust`                  | `color` (`{r,g,b}`), `scale` (number, default `1.0`)                              |
| `dust_color_transition` | `fromColor` (`{r,g,b}`), `toColor` (`{r,g,b}`), `scale` (number, default `1.0`)   |
| `dust_pillar`           | `block` (string)                                                                  |
| `effect`                | `color` (`{r,g,b}` — optional, omit for no tint), `power` (number, default `1.0`) |
| `entity_effect`         | `color` (`{a,r,g,b}` — alpha + RGB)                                               |
| `falling_dust`          | `block` (string)                                                                  |
| `flash`                 | `color` (`{a,r,g,b}` — alpha + RGB)                                               |
| `instant_effect`        | `color` (`{r,g,b}` — optional, omit for no tint), `power` (number, default `1.0`) |
| `item`                  | `item` (string), `count` (number, default `1`)                                    |
| `sculk_charge`          | `roll` (number, default `0.0`)                                                    |
| `shriek`                | `delay` (number, default `0`)                                                     |
| `tinted_leaves`         | `color` (`{a,r,g,b}` — alpha + RGB)                                               |
| `trail`                 | `target` (vec), `color` (`{r,g,b}`), `duration` (number, default `20`)            |
| `vibration`             | `from` (vec), `arrivalInTicks` (number, default `1`)                              |

Simple particles (no `data` needed): `angry_villager`, `ash`, `bubble`, `bubble_column_up`, `bubble_pop`, `campfire_cosy_smoke`, `campfire_signal_smoke`, `cherry_leaves`, `cloud`, `composter`, `copper_fire_flame`, `crit`, `crimson_spore`, `current_down`, `damage_indicator`, `dolphin`, `dripping_dripstone_lava`, `dripping_dripstone_water`, `dripping_honey`, `dripping_lava`, `dripping_obsidian_tear`, `dripping_water`, `dust_plume`, `egg_crack`, `elder_guardian`, `electric_spark`, `enchant`, `enchanted_hit`, `end_rod`, `explosion`, `explosion_emitter`, `falling_dripstone_lava`, `falling_dripstone_water`, `falling_honey`, `falling_lava`, `falling_nectar`, `falling_obsidian_tear`, `falling_spore_blossom`, `falling_water`, `firefly`, `firework`, `fishing`, `flame`, `glow`, `glow_squid_ink`, `gust`, `gust_emitter_large`, `gust_emitter_small`, `happy_villager`, `heart`, `infested`, `item_cobweb`, `item_slime`, `item_snowball`, `landing_honey`, `landing_lava`, `landing_obsidian_tear`, `large_smoke`, `lava`, `mycelium`, `nautilus`, `note`, `ominous_spawning`, `pale_oak_leaves`, `poof`, `portal`, `raid_omen`, `rain`, `reverse_portal`, `scrape`, `sculk_charge_pop`, `sculk_soul`, `small_flame`, `small_gust`, `smoke`, `sneeze`, `snowflake`, `sonic_boom`, `soul`, `soul_fire_flame`, `spit`, `splash`, `spore_blossom_air`, `squid_ink`, `sweep_attack`, `totem_of_undying`, `trial_omen`, `trial_spawner_detection`, `trial_spawner_detection_ominous`, `underwater`, `vault_connection`, `warped_spore`, `wax_off`, `wax_on`, `white_ash`, `white_smoke`, `witch`.

```lua
world:particle("minecraft:flame", { x = 0, y = 65, z = 0 })
world:particle("minecraft:heart", { x = 0, y = 65, z = 0 }, {
  count = 5,
  delta = { 0.5, 0.5, 0.5 },
  speed = 0.1
})
world:particle("minecraft:dust", { x = 0, y = 65, z = 0 }, {
  data = { color = { 1, 0, 0 }, scale = 1 },
  count = 5,
})
```

### `world:playSound(id, pos, volume?, pitch?)`

Plays a sound at a position for all players in the world.

- `id` (`string`) — Sound identifier
- `pos` (`table`) — `{x, y, z}` position
- `volume` (`number`, default `1.0`) — Volume
- `pitch` (`number`, default `1.0`) — Pitch

```lua
world:playSound("minecraft:entity.experience_orb.pickup", vec(0, 64, 0))
world:playSound("minecraft:entity.ender_dragon.growl", vec(0, 64, 0), 2.0, 0.5)
```


## World Effects

### `world:getBiome(pos)`

Returns the biome ID at a position, or `nil`.

- `pos` (`table`) — `{x, y, z}` position

```lua
local biome = world:getBiome({ x = 0, y = 64, z = 0 }) -- "minecraft:plains"
```

### `world:getBorder()`

Returns a proxy table for the world border with read/write access:

| Field | Type | Description |
|---|---|---|
| `.center` | `{x, z}` | Border center (table with `x`, `z`) |
| `.size` | `number` | Border diameter |
| `.damage` | `number` | Damage per block outside the border |
| `.warningTime` | `number` | Warning time in seconds |
| `.warningBlocks` | `number` | Warning distance in blocks |
| `.damageThreshold` | `number` | Safe zone distance before damage starts |

```lua
local b = world:getBorder()
print(b.center.x, b.center.z, b.size)
b.size = 500
b.center = { x = 100, z = 100 }
```

### `world:explode(pos, power, opts?)`

Creates an explosion at a position.

- `pos` (`table`) — `{x, y, z}` center
- `power` (`number`) — Explosion power
- `opts` (`table`, optional) — `{ fire = bool, destruction = "break"|"destroy"|"none" }`

```lua
world:explode({ x = 0, y = 64, z = 0 }, 4.0, { fire = true })
```

### `world:strike(pos, opts?)`

Spawns a lightning bolt at a position.

- `pos` (`table`) — `{x, y, z}` strike position
- `opts` (`table`, optional) — `{ effect = bool }` (true = cosmetic only, no damage/fire)

```lua
world:strike({ x = 0, y = 64, z = 0 })
world:strike({ x = 0, y = 64, z = 0 }, { effect = true })
```

## Holograms

### `world:spawnHologram(pos, text, opts?)`

Spawns a hologram at the given position.

- `pos` (`table`) — `{x, y, z}` spawn position
- `text` (`string`) — Display text
- `opts` (`table`, optional) — Hologram options

`opts` table:

| Option       | Type      | Default      | Description                                                          |
|--------------|-----------|--------------|----------------------------------------------------------------------|
| `alignment`  | `string`  | `"center"`   | Text alignment                                                       |
| `billboard`  | `string`  | `"center"`   | Billboard mode (`"fixed"`, `"vertical"`, `"horizontal"`, `"center"`) |
| `lineWidth`  | `number`  | `200`        | Line width                                                           |
| `background` | `number`  | `0x40000000` | ARGB background color                                                |
| `opacity`    | `number`  | `255`        | Text opacity (0–255)                                                 |
| `shadow`     | `boolean` | `false`      | Text shadow                                                          |
| `seeThrough` | `boolean` | `false`      | See-through                                                          |
| `glowing`    | `boolean` | `false`      | Glowing effect                                                       |

Returns a [Hologram](/reference/hologram-api).

```lua
local holo = world:spawnHologram(vec(0, 70, 0), "&6Welcome!", {
    billboard = "center",
    background = 0x80000000
})
```

## Broadcasting

### `world:broadcastInRange(text, pos, range, overlay?)`

Broadcasts a message to players within range of a position.

- `text` (`string`) — Message text
- `pos` (`table`) — `{x, y, z}` origin position
- `range` (`number`) — Radius in blocks
- `overlay` (`number`, optional) — If provided, sends as title overlay with the given duration in ticks

```lua
world:broadcastInRange("&cDanger nearby!", vec(0, 64, 0), 20)
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

Returns the [`Region`](/reference/region-api) wrapper for the given ID, or `nil` if no region with that ID exists in
this world.

```lua
local r = world:getRegion(42)
if r then r:destroy() end
```

### `world:getRegionsAt(pos)`

Returns a sequence of all [`Region`](/reference/region-api) wrappers in this world that contain the given position.
Returns `{}` if none. Regions may overlap, so multiple results are possible.

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
