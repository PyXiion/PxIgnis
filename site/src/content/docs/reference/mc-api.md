---
title: mc.* API
description: Global Lua API table for server interaction, scheduling, networking, events, and persistence.
---

The `mc` table is the main entry point for all PxIgnis Lua scripting. It provides access to the
server, entities, worlds, storage, events, and async operations.

## World & Server Info

### `mc.time()`
Returns the current world age in ticks.

```lua
local t = mc.time()
```

### `mc.players()`
Returns a list of all online players as player wrappers.

```lua
for _, p in ipairs(mc.players()) do
  p:sendMessage("Hello!")
end
```

### `mc.onlineCount`
The number of currently online players.

```lua
if mc.onlineCount == 0 then
  mc.broadcast("Server is empty...")
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

### `mc.runtimeNamespace`
Returns the runtime namespace string (e.g., `"minecraft"`).

### `mc.mapped(className)`
Translates an obfuscated class name to its mapped name.

## Chat & Broadcasting

### `mc.broadcast(text, overlay?)`
Broadcasts a message to all online players. Second argument, if `true`, sends an overlay
(toast-style) message instead of chat.

```lua
mc.broadcast("Server is restarting soon!")
mc.broadcast("Welcome!", true) -- overlay
```

## Utilities

### `mc.dump(obj, depth?)`
Recursively prints a Lua value's structure for debugging. Optional `depth` limits nesting.

```lua
mc.dump(mc.players(), 2)
```

### `mc.getMetatable(name)`
Returns a shared metatable by name. See [MetaTableRegistry](/api/#metatableregistry).

```lua
local meta = mc.getMetatable("vec")
```

## Scheduling & Async

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

### `mc.sleep(ticks)`
Yields the current coroutine for the given number of ticks. Must be called inside a
coroutine (e.g., an event handler or scheduled callback).

```lua
mc.schedule(0, function()
  mc.broadcast("Wait for it...")
  mc.sleep(40)
  mc.broadcast("2 seconds later!")
end)
```

### `mc.fetch(url)` / `mc.fetch({...})`
Performs an asynchronous HTTP request. Yields the current coroutine and resumes with the
response table. Accepts a URL string or a config table.

```lua
-- Simple GET
local res = mc.fetch("https://api.example.com/data")
if res.status == 200 then
  mc.broadcast(res.body)
end

-- Config table
local res = mc.fetch({
  url = "https://api.example.com/data",
  method = "POST",
  headers = { ["Content-Type"] = "application/json" },
  body = '{"key": "value"}'
})
```

The response table has `.status`, `.headers`, `.body`, and `.json` (lazy-parsed via
metatable — accessed as `res.json`).

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
  unbreakable = true,
  attackDamage = 20
})
```

See [ItemStack API](/reference/itemstack-api) for details.

## Storage

### `mc.data`
Global persistent data table. See [Storage](/reference/storage) for details.

```lua
mc.data.welcomeMessage = "Welcome!"
mc.data.visits = (mc.data.visits or 0) + 1
```

## Events

### `mc.on(event, handler)`
Registers a handler for a server event. Returns `true` on success. Cancellable events:
return `false` to cancel. See [Events](/reference/events) for the full event list.

```lua
mc.on("player_join", function(player)
  player:sendMessage("Welcome, " .. player.name .. "!")
end)

mc.on("player_block_break", function(player, blockPos)
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