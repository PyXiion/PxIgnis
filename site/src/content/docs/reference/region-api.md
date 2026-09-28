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

Subscribe with `region:on(event, handler, opts?)`. Handlers get an event table `e` with `e.region` and the fields
below. Region events are not cancellable. Options (`priority`, `receiveCancelled`, `throttle`) work as for
[`mc.on`](/reference/events#options).

| Event     | Fields                                  | When                                                        |
|-----------|-----------------------------------------|-------------------------------------------------------------|
| `enter`   | `entity`, `player?`                     | An entity moves into the region (or is loaded inside it)    |
| `leave`   | `entity`, `player?`                     | An entity moves out (or is unloaded)                        |
| `move`    | `entity`, `player?`, `from`, `to`       | An entity inside changes position, including teleports      |
| `death`   | `entity`, `player?`, `source`, `amount` | An entity inside dies                                       |
| `tick`    | —                                       | Every server tick                                           |
| `destroy` | —                                       | The region is destroyed; clean up your script state here    |

`e.player` is set (to the same object as `e.entity`) when the entity is a player, so player-only handlers start with
`if not e.player then return end`.

```lua
r:on("enter", function(e)
  if e.player then e.player:sendMessage("Welcome!") end
end)

r:on("move", function(e)
  if (e.to - e.from):length() > 50 then
    e.entity:sendMessage("That was a big jump!")
  end
end, { throttle = 5 })

r:on("tick", function()
  for _, p in ipairs(r.players) do
    p:addEffect("minecraft:regeneration", 40, 0)
  end
end)
```

The old names (`entity_enter`, `player_enter`, `entity_leave`, `player_leave`, `entity_move`, `player_move`,
`entity_death`, `player_death`) still work with positional arguments and log a deprecation warning.

## Methods

### `region:on(event, callback, opts?)`

See the [Events](#events) section above for details. Returns a handler ID (integer) that can be passed to
`region:off(id)` to unsubscribe.

### `region:off(id)`

Removes a handler previously registered with `region:on`. Returns `true` if the handler was found and removed, `false`
otherwise.

```lua
local id = r:on("enter", function(e) e.entity:sendMessage("Hi!") end)
r:off(id)  -- unsubscribes
```

### `region:destroy()`

Destroys the region. No `leave` events fire on destruction.

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

Regions are destroyed on `/ignis reload`. No `leave` events fire during reload cleanup. To persist region data
across reloads, store bounds in `mc.data` and recreate in `server_start`:

```lua
mc.on("server_start", function()
  local zones = mc.data.zones or {}
  for _, z in ipairs(zones) do
    local r = world:createRegion(z.A, z.B)
    r:on("enter", function(e)
      e.entity:sendMessage("Welcome!")
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
