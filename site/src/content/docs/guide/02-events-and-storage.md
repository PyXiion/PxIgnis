---
title: 2. Events and storage
description: React to server events and persist data across reloads with mc.on, mc.emit, mc.data, and player.data.
---

In [part 1](/guide/01-your-first-command) you built commands. Now let's make your script react to the world and remember things between reloads.

In this guide you will learn how to:

- Listen to server events with `mc.on`.
- Cancel events with `e:cancel()` and order handlers with priorities.
- Fire your own events with `mc.emit`.
- Save data globally with `mc.data` and per-player with `player.data`.

All examples use [Nova lambda syntax](/reference/language), so the first line of each file is:

```lua
--# nova syntax
```

## 1. Reacting to events with `mc.on`

Minecraft fires events constantly — when a player joins, breaks a block, chats, dies, and so on. `mc.on` lets you run code whenever one of those events happens.

Syntax:

```lua
mc.on(eventName, handler)
```

- `eventName` is a string like `"player.join"`.
- `handler` is a function that receives one argument: the event table `e`, with the event's fields.
- `mc.on` returns a numeric ID. Save it if you want to stop listening later with `mc.off(id)`.

Example:

```lua
--# nova syntax
mc.on("player.join") \{ e ->
    mc.broadcast("Welcome, " .. e.player.name .. "!")
}
```

`e.player` is the player who joined. Each event has its own fields, see the [Events reference](/reference/events).

To unsubscribe later:

```lua
--# nova syntax
local joinId = mc.on("player.join") \{ e ->
    mc.broadcast("Welcome!")
}

-- later...
mc.off(joinId)
```

## 2. Cancelling events

Some events are cancellable. Call `e:cancel()` and Minecraft stops the action.

```lua
--# nova syntax
mc.on("block.break") \{ e ->
    if not e.player:hasPermission("build") then
        e.player:sendMessage("You cannot break blocks here.")
        e:cancel()
    end
}
```

In this example, `hasPermission("build")` checks whether the player has the `build` permission. You can use any permission node you have configured on your server.

Cancellable events include `block.break`, `block.place`, `player.chat`, `entity.hurt`, and several others. See the [Events reference](/reference/events) for the full list.

Once an event is cancelled, the handlers after it are skipped. Handlers run by **priority** (higher first), so put checks
that may cancel before the rest:

```lua
--# nova syntax
mc.on("block.break", { priority = "high" }) \{ e ->
    if isProtected(e.pos) then e:cancel() end
}

mc.on("block.break") \{ e ->
    -- not called for protected blocks
    e.player.data.mined = (e.player.data.mined or 0) + 1
}
```

A handler that wants to see cancelled events too passes `{ receiveCancelled = true }`. If a handler of a cancellable
event fails with an error, the event is cancelled, so a bug in a protection script does not open the door.

## 3. Custom events with `mc.emit`

You can fire your own events from Lua. This is useful when you want separate scripts to react to the same thing.

```lua
--# nova syntax
-- rewards.lua
mc.on("my_mod:boss_killed") \{ player, bossName ->
    player:sendMessage("You defeated " .. bossName .. "!")
    player.data.bossKills = (player.data.bossKills or 0) + 1
}
```

Then elsewhere:

```lua
--# nova syntax
-- bossfight.lua
mc.emit("my_mod:boss_killed", somePlayer, "Ender Dragon")
```

## 4. Global persistent data with `mc.data`

`mc.data` is a special table that is saved to `config/ignis/storage/global.json`. Anything you put in it survives `/ignis reload` and server restarts.

```lua
--# nova syntax
mc.data.joins = (mc.data.joins or 0) + 1

mc.on("player.join") \{ e ->
    mc.broadcast("Visitor #" .. mc.data.joins .. "!")
}
```

**Allowed values:** numbers, strings, booleans, tables, and `nil` (to delete a key).

**Not allowed:** functions, userdata, threads, and tables that reference themselves (cyclic tables). If you try to save those, the server logs an error.

## 5. Per-player data with `player.data`

Every player wrapper has its own persistent table at `player.data`. It works exactly like `mc.data`, but each player gets a separate file on disk.

```lua
--# nova syntax
mc.on("player.join") \{ e ->
    local visits = (e.player.data.visits or 0) + 1
    e.player.data.visits = visits
    e.player:sendMessage("This is visit #" .. visits)
}
```

Per-player data is saved when the player disconnects and when `/ignis reload` runs. It is removed from memory on disconnect, but stays on disk so it is available next time the player joins.

## 6. Putting it together: a tiny coin system

This example combines commands, events, and per-player storage.

```lua
--# nova syntax
local function giveCoins(player, amount)
    player.data.coins = (player.data.coins or 0) + amount
    player:sendMessage("You have " .. player.data.coins .. " coins")
end

-- /coins gives 10 coins
register("coins") \{ ctx ->
    giveCoins(ctx.player, 10)
}

-- Killing another player gives 50 coins
mc.on("player.kill") \{ e ->
    giveCoins(e.player, 50)
}

-- Dying makes you lose 10% of your coins
mc.on("entity.death") \{ e ->
    local player = e.player
    if not player then return end -- a mob died
    local coins = player.data.coins or 0
    local lost = math.floor(coins * 0.1)
    player.data.coins = coins - lost
    player:sendMessage("You lost " .. lost .. " coins")
}
```

How it works:

1. `giveCoins` reads the player's saved coin count, adds the amount, and saves it back.
2. The `coins` command calls it for the player who ran the command.
3. The `player.kill` event gives a reward.
4. The `entity.death` event takes a penalty when the one who died is a player.

Because `player.data` persists, your coin total survives disconnects and reloads.

## 7. Notes and limitations

- Storage saves automatically. You do not need to call a save method.
- Deep nested tables work directly: `mc.data.guilds.mine.members.leader = player.name`.
- Event handlers run as coroutines, so they can wait on the `async` module directly:

```lua
--# nova syntax
local async = require "async"

mc.on("player.join") \{ e ->
    async.sleep(100) -- 5 seconds
    e.player:sendMessage("Delayed hello!")
}
```

- Handlers run on the server thread. A handler that runs too long (5 seconds by default, e.g. an endless loop) is
  stopped with an error so it cannot freeze the server.

## Next steps

Keep going with the topics that match what you want to build:

**Build a whole game**

- [Tutorial: an arena minigame](/tutorial) — commands, regions, rounds, kits, statistics and more, in one
  script that grows chapter by chapter

**More about events and data**

- [Events reference](/reference/events) — complete event list and handler signatures
- [Storage reference](/reference/storage) — allowed types and save behavior
- [Examples: persistence](/examples/persistence) — advanced data patterns
- [Examples: events](/examples/events) — larger event-driven scripts

**Built-in helpers**

- [Libraries](/docs/libraries/overview) — formatting, simple registrations, chest GUIs
- [Async API](/reference/async-api) — delays and HTTP requests

**Game systems**

- [Region API](/reference/region-api) — protect areas and react to movement
- [Sidebar API](/reference/sidebar-api) — show persistent on-screen info
- [Hologram API](/reference/hologram-api) — floating text displays
- [Inventory API](/reference/inventory-api) — custom menus and storage UIs
- [Mob AI](/reference/mob-ai) — script custom mob behavior
