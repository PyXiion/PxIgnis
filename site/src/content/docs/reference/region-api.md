---
title: Region
description: Spatial area with event subscriptions for entity entrance, exit, movement, and death.
---

The Region provides access to a spatial area (axis-aligned bounding box) within a world.

Metatable name: `"region"`

## Creation

### `world:createRegion(posA, posB)`

Creates a new region spanning between two corners.

```lua
local r = world:createRegion(vec(0, 0, 0), vec(100, 64, 100))
```

### `world.regions`

Returns a sequence of all live region wrappers in this world (in creation order).

```lua
for _, r in ipairs(world.regions) do
  print(r.id, r:getBounds().A.x)
end
```

## Lookup

### `mc.getRegion(id)`/`world:getRegion(id)`

Returns the [`Region`](/reference/region-api) wrapper for the given ID, or `nil` if no region with that ID exists.
ID is unique among worlds.

```lua
local r = mc.getRegion(42)
if r then
  print("Found region at", r:getBounds().A)
end
```

### `world:getRegionsAt(pos)`

Returns a sequence of all [`Regions`](/reference/region-api) in this world that contain the given position.
Returns an empty sequence `{}` if no regions contain the position. Since regions may overlap, the result
can have multiple entries.

```lua
local pos = vec(50, 64, 50)
local regions = world:getRegionsAt(pos)
for _, r in ipairs(regions) do
  print("In region", r.id)
end
```

## Properties

### `region.id`

**type:** `number`

A session-unique integer assigned at creation. Stable until `/ignis reload` or server restart. Read-only.

### `region.world`

**type:** [`World`](/reference/world-api)

The world this region belongs to. Read-only.

### `region:getBounds()`

Returns a table with the two bounding corners as `A` and `B`.

```lua
local b = r:getBounds()
-- b.A = { x = 0, y = 0, z = 0 }
-- b.B = { x = 100, y = 64, z = 100 }
```

`A` is always the min corner, `B` the max corner (normalized). Read-only; use `setBounds` to mutate.

### `region.players`

**type:** sequence of [`Player`](/reference/player-api)

Array of player wrappers currently inside the region. Read-only.

### `region.entities`

**type:** sequence of [`Entity`](/reference/entity-api) or [`Player`](/reference/player-api)

Array of entity wrappers currently inside the region. Read-only.

## Events

All region events are subscribed via `region:on(event, callback, opts?)`. The `opts` table can include:

| Option     | Type     | Default | Description                                                           |
|------------|----------|---------|-----------------------------------------------------------------------|
| `throttle` | `number` | `0`     | Minimum ticks between invocations of this callback. `0` = no throttle |

### `region:on("entity_enter", function(entity))`

Fires when an entity moves into the region.

- `entity` ([`Entity`](/reference/entity-api)) — The entering entity

```lua
r:on("entity_enter", function(e)
  e:sendMessage("Welcome!")
end)
```

### `region:on("entity_leave", function(entity))`

Fires when an entity moves out of the region.

```lua
r:on("entity_leave", function(e)
  e:sendMessage("Goodbye!")
end)
```

### `region:on("entity_move", function(entity, from, to))`

Fires each tick while an entity is inside the region and changes position. Fires for any position change, including
teleports.

- `entity` ([`Entity`](/reference/entity-api)) — The moving entity
- `from` ([`Vec`](/reference/vector-api)) — Previous position
- `to` ([`Vec`](/reference/vector-api)) — Current position

```lua
r:on("entity_move", function(e, from, to)
  local dist = (to - from):length()
  if dist > 50 then
    e:sendMessage("That was a big jump!")
  end
end, { throttle = 5 })
```

### `region:on("player_enter", function(player))`

Convenience over `entity_enter`, fires only for players. Shares the event lifecycle with `entity_enter`.

### `region:on("player_leave", function(player))`

Convenience over `entity_leave`, fires only for players.

### `region:on("player_move", function(player, from, to))`

Convenience over `entity_move`, fires only for players.

### `region:on("entity_death", function(entity, source, amount))`

Fires when a tracked entity inside the region dies.

- `entity` ([`Entity`](/reference/entity-api)) — The dying entity
- `source` (`string`) — Damage source name
- `amount` (`number`) — Damage amount

### `region:on("player_death", function(player, source))`

Fires when a player inside the region dies.

- `player` ([`Player`](/reference/player-api)) — The dying player
- `source` (`string`) — Damage source name

### `region:on("tick", function())`

Fires every server tick.

```lua
r:on("tick", function()
  for _, p in ipairs(r.players) do
    p:addEffect("minecraft:regeneration", 40, 0)
  end
end)
```

### `region:on("destroy", function())`

Fires when the region is destroyed, allowing cleanup of user script state.

## Methods

### `region:on(event, callback, opts?)`

See the [Events](#events) section above for details. Returns a handler ID (integer) that can be passed to
`region:off(id)` to unsubscribe.

### `region:off(id)`

Removes a handler previously registered with `region:on`. Returns `true` if the handler was found and removed, `false`
otherwise.

```lua
local id = r:on("entity_enter", function(e) e:sendMessage("Hi!") end)
r:off(id)  -- unsubscribes
```

### `region:destroy()`

Destroys the region. No `entity_leave` events fire on destruction.

```lua
r:destroy()
```

### `region:contains(pos)`

Returns `true` if the position `{ x, y, z }` is inside the region's bounds.

```lua
if r:contains({ x = 50, y = 32, z = 50 }) then
  mc.broadcast("Inside!")
end
```

### `region:setBounds(posA, posB)`

Updates the region bounds to span between the two given corners. Corners are auto-normalized — the region re-evaluates
all contained entities and fires enter/leave transitions as needed.

```lua
r:setBounds({ x = -10, y = 0, z = -10 }, { x = 10, y = 32, z = 10 })
```

## Lifetime

Regions are destroyed on `/ignis reload`. No `entity_leave` events fire during reload cleanup. To persist region data
across reloads, store bounds in `mc.data` and recreate in `server_start`:

```lua
mc.on("server_start", function()
  local zones = mc.data.zones or {}
  for _, z in ipairs(zones) do
    local r = world:createRegion(z.A, z.B)
    r:on("entity_enter", function(e)
      e:sendMessage("Welcome!")
    end)
  end
end)

--- Persistent state:
local zones = mc.data.zones or {}
table.insert(zones, { A = { x = 0, y = 0, z = 0 }, B = { x = 100, y = 64, z = 100 } })
mc.data.zones = zones
```

## Notes

- **Teleport detection**: Move events fire for any position change, including teleports. Filter by distance in your
  callback if you want to ignore teleports.
- **Unloaded chunks**: Entities in unloaded chunks do not tick, so position changes go undetected. Membership is
  reconciled when the chunk reloads (enter/leave fires if needed).
- **Performance**: `region:getBounds()`, `region.entities`, and `region.players` allocate new tables on each access.
  Avoid calling in tight loops.
- **Bounds are inclusive of min, exclusive of max** (standard Minecraft convention). A point at exactly `max` is
  considered outside.
