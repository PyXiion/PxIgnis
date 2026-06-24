---
title: mc.* API
description: Global Lua API table for server interaction, scheduling, networking, events, and persistence.
---

The `mc` table is the main entry point for all PxIgnis Lua scripting. It provides access to the
server, entities, worlds, storage, events, and async operations.

`mc` exposes both **functions** (must be called with `()`) and **static properties** (read directly).
Functions are documented as `mc.fn()`, properties as `mc.prop`.

## World & Server Info

### `mc.time()`
Returns the current Unix timestamp in seconds (e.g. `1700000000.123`).

```lua
local t = mc.time()
```

For world-daylight-time in ticks, use `world.time` instead.

### `mc.players`

A list of all online [players](/reference/player-api).

```lua
for _, p in ipairs(mc.players) do
  p:sendMessage("Hello!")
end
```

### `mc.onlineCount`

The number of currently online players.

```lua
if mc.onlineCount == 0 then
  mc.broadcast("Server is empty... :(")
end
```

### `mc.world(name)`
Gets a world by name. Returns `nil` if not found.

```lua
local overworld = mc.world("minecraft:overworld")
```

### `mc.getEntity(uuid)`
Gets an entity by its UUID string. Returns the entity wrapper or `nil`.

```lua
local entity = mc.getEntity("123e4567-e89b-12d3-a456-426614174000")
```

## Chat & Broadcasting

### `mc.broadcast(text, overlayDuration?)`
Broadcasts a message to all online players. If `overlayDuration` is a number (in ticks),
sends it as a title overlay (toast-style) instead of chat. The overlay fades in over 20
ticks (1s), stays for the given duration, then fades out over 20 ticks.

```lua
mc.broadcast("Server is restarting soon!")
mc.broadcast("Welcome!", 70) -- overlay, stays ~3.5s
```

## Utilities

### `mc.dump(obj, depth?)`
Recursively prints a Lua value's structure for debugging. Optional `depth` limits nesting
(default 3).

```lua
mc.dump(mc.players, 2)
```

### `mc.getMetatable(name)`
Returns a shared metatable by name. See [MetaTableRegistry](/api/#metatableregistry).

```lua
local meta = mc.getMetatable("vec")  -- "vec", "entity", "player", "world", "bossbar", ...
```

## Scheduling

### `mc.schedule(delay, callback)`
Runs `callback` once after `delay` ticks (20 ticks = 1 second). Returns a task ID.

```lua
mc.schedule(40, function()
  mc.broadcast("2 seconds have passed!")
end)
```

### `mc.scheduleRepeating(delay, interval, callback)`
Runs `callback` repeatedly, first after `delay` ticks, then every `interval` ticks.
Returns a task ID.

```lua
local id = mc.scheduleRepeating(0, 20, function()
  mc.broadcast("This repeats every second")
end)
```

### `mc.cancelTask(id)`
Cancels a scheduled task. Returns `false` if the ID was never valid or already cancelled.

```lua
mc.cancelTask(id)
```

## Command Execution

### `mc.execute(cmd, opts?)`

Executes a command as the console (or as a specified entity). Returns `true, exitCode` on success,
or `false, errorMessage` on failure.

- `cmd` (`string`) — Command to execute
- `opts` (`table`, optional) — Options:
  - `as` (`entity`) — Execute as this entity (affects `@s` and command context)
  - `at` (`pos`) — Execute at this position

```lua
local ok, result = mc.execute("say Hello!")
local ok, err = mc.execute("kick nonexistent")

-- Execute as a player at their position
mc.execute("kill @e[type=pig,distance=..5]", { as = player, at = player.pos })
```
## Items

### `mc.createItem(id, [count | components])`
Creates an item stack wrapper. `id` is the item identifier (e.g., `"diamond"`). Accepts
either a count number or a component table.

```lua
local stack = mc.createItem("diamond", 1)
-- With custom data
local sword = mc.createItem("diamond_sword", {
  count = 1,
  name = "&cLegendary Sword",
  lore = { "&7Wielded by heroes" },
  custom_model_data = 1001,
  unbreakable = true
})
```

You can also pass a single table with an `id` field:
```lua
local sword = mc.createItem({ id = "diamond_sword", count = 1, name = "&cEpic Sword" })
```

See [ItemStack API](/reference/itemstack-api) for details.

### `mc.serialise(type, obj)`
### `mc.deserialise(type, json)`

Serialise and deserialise items or inventories to/from JSON strings. Useful for saving
stacks to `mc.data` or transferring over `mc.fetch`.

- `type` (`string`) — `"item"` or `"inventory"`
- `obj` — An [ItemStack](/reference/itemstack-api) or [Inventory](/reference/inventory-api) wrapper
- `json` (`string`) — JSON string produced by `mc.serialise`

```lua
local stack = mc.createItem("diamond", 1)
local json = mc.serialise("item", stack)

local restored = mc.deserialise("item", json)
player:give(restored)
```

Items returned by `mc.serialise` can be stored in `mc.data` and restored across reloads.

## Storage

### `mc.data`
Global persistent data table. See [Storage](/reference/storage) for details.

```lua
mc.data.welcomeMessage = "Welcome!"
mc.data.visits = (mc.data.visits or 0) + 1
```


## Boss Bars

### `mc.createBossBar(title, color?, style?)`

Creates a boss bar visible to all players. See [BossBar](/reference/bossbar-api).

- `title` (`string`) — Boss bar display title
- `color` (`string`, default `"white"`) — `"pink"`, `"blue"`, `"red"`, `"green"`, `"yellow"`, `"purple"`, `"white"`
- `style` (`string`, default `"progress"`) — `"progress"`, `"notched_6"`, `"notched_10"`, `"notched_12"`, `"notched_20"`

```lua
local bar = mc.createBossBar("&cBoss Fight", "red", "notched_6")
bar:addPlayer(player)
bar.progress = 0.5
```

### `mc.bossBars()`

Returns a table of all active boss bars.

### `mc.getBossBar(uuid)`

Returns the boss bar with the given UUID, or `nil`.
## Events

### `mc.on(event, handler)`
Registers a handler for a server event. Returns a numeric handler ID. Cancellable events:
return `false` to cancel. See [Events](/reference/events) for the full event list.

```lua
mc.on("player_join", function(player)
  player:sendMessage("Welcome, " .. player.name .. "!")
end)

mc.on("player_block_break", function(player, pos, blockId)
  if player.gamemode == "survival" then
    return false -- cancel
  end
end)
```

### `mc.emit(event, ...)`
Programmatically emits an event, triggering all registered handlers.

```lua
mc.emit("script:custom_event", player, "Hello!")

mc.on("script:custom_event", function(p, msg)
    p:sendMessage(msg)
end)

mc.on("script:custom_event", function(p, msg)
    print("MESSAGE LOG: " .. p.name .. " got " .. msg)
end)
```