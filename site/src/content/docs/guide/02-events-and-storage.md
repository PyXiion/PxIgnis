---
title: 2. Events and storage
description: React to server events and persist data across reloads with mc.on, mc.emit, mc.data, and player.data.
---

In [part 1](/guide/01-your-first-command) you built commands. Now let's make your script react to the world and remember things between reloads.

In this guide you will learn how to:

- Listen to server events with `mc.on`.
- Cancel events by returning `false`.
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

- `eventName` is a string like `"player_join"`.
- `handler` is a function that receives event-specific arguments.
- `mc.on` returns a numeric ID. Save it if you want to stop listening later with `mc.off(id)`.

Example:

```lua
--# nova syntax
mc.on("player_join") \{ player ->
    mc.broadcast("Welcome, " .. player.name .. "!")
}
```

The handler receives `player`, the player who joined. Each event sends its own arguments, so check the [Events reference](/reference/events) for details.

To unsubscribe later:

```lua
--# nova syntax
local joinId = mc.on("player_join") \{ player ->
    mc.broadcast("Welcome!")
}

-- later...
mc.off(joinId)
```

## 2. Cancelling events

Some events are cancellable. If your handler returns `false`, Minecraft stops the action.

```lua
--# nova syntax
mc.on("player_block_break") \{ player, pos, blockId ->
    if not player:hasPermission("build") then
        player:sendMessage("You cannot break blocks here.")
        return false
    end
}
```

In this example, `player:hasPermission("build")` checks whether the player has the `build` permission. You can use any permission node you have configured on your server.

Cancellable events include `player_block_break`, `player_block_place`, `player_chat`, `player_hurt`, and several others. See the [Events reference](/reference/events) for the full list.

If the event is cancelable and you return nothing (or true) the event will proceed normally.

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

mc.on("player_join") \{ player ->
    mc.broadcast("Visitor #" .. mc.data.joins .. "!")
}
```

**Allowed values:** numbers, strings, booleans, tables, and `nil` (to delete a key).

**Not allowed:** functions, userdata, threads, and tables that reference themselves (cyclic tables). If you try to save those, the server logs an error.

## 5. Per-player data with `player.data`

Every player wrapper has its own persistent table at `player.data`. It works exactly like `mc.data`, but each player gets a separate file on disk.

```lua
--# nova syntax
mc.on("player_join") \{ player ->
    local visits = (player.data.visits or 0) + 1
    player.data.visits = visits
    player:sendMessage("This is visit #" .. visits)
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
mc.on("player_kill") \{ player, target, damageSource ->
    giveCoins(player, 50)
}

-- Dying makes you lose 10% of your coins
mc.on("player_death") \{ player, damageType ->
    local coins = player.data.coins or 0
    local lost = math.floor(coins * 0.1)
    player.data.coins = coins - lost
    player:sendMessage("You lost " .. lost .. " coins")
}
```

How it works:

1. `giveCoins` reads the player's saved coin count, adds the amount, and saves it back.
2. The `coins` command calls it for the player who ran the command.
3. The `player_kill` event gives a reward.
4. The `player_death` event takes a penalty.

Because `player.data` persists, your coin total survives disconnects and reloads.

## 7. Notes and limitations

- Storage saves automatically. You do not need to call a save method.
- Deep nested tables work directly: `mc.data.guilds.mine.members.leader = player.name`.
- Some APIs, such as `mc.sleep` and `mc.fetch`, are async and cannot be used directly inside event handlers. If you need them, defer the work to the scheduler:

```lua
--# nova syntax
mc.on("player_join") \{ player ->
    mc.schedule(0) \{
        -- async work goes here
        mc.sleep(1000)
        player:sendMessage("Delayed hello!")
    }
}
```

## Next steps

Keep going with the topics that match what you want to build:

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
