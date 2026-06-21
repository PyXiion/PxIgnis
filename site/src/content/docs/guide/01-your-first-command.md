---
title: 1. Your first command
description: Build your first PxIgnis command step by step — arguments, permissions, timers, async delays, and HTTP requests.
---

Create `config/ignis/mycommands.lua` and run `/ignis reload` after each change to see your commands in-game.

## 1. Simplest command

```lua
register("ping", function(ctx)
    ctx.player:sendMessage("Pong!")
end)
```

The handler receives a `ctx` table with `ctx.player`. Call methods on it to interact with the player.

## 2. Arguments

Add a `<target:player>` argument — the value arrives as a positional parameter:

```lua
register("heal <target:player>", function(ctx, target)
    target:heal(20)
    ctx.player:sendMessage("Healed " .. target.name)
end)
```

Wrap an argument in `[...]` to make it optional. Missing optional args are `nil`:

```lua
register("heal [<target:player>]", function(ctx, target)
    local t = target or ctx.player
    t:heal(20)
end)
```

See [Registering Commands](/reference/commands-api) for all argument types.

## 3. Choice arguments

A choice argument limits input to predefined options and provides tab completions:

```lua
register("gamemode <mode:choice=survival,creative,adventure,spectator> [<target:player>]", function(ctx, mode, target)
    local t = target or ctx.player
    t:sendMessage("Set " .. t.name .. " to " .. mode)
end)
```

## 4. Timers with mc.schedule

Schedule a one-shot action with a delay in ticks (20 ticks = 1 second):

```lua
register("callback", function(ctx)
    ctx.player:sendMessage("You'll be healed in 5 seconds...")
    mc.schedule(100, function()
        ctx.player:heal(20)
        ctx.player:sendMessage("Healed!")
    end)
end)
```

Repeating timers with `mc.scheduleRepeating(delay, interval, callback)`:

```lua
register("countdown <seconds:int>", function(ctx, seconds)
    local remaining = seconds
    local id -- declare first so the callback can see it
    id = mc.scheduleRepeating(0, 20, function()
        remaining = remaining - 1
        if remaining <= 0 then
            mc.broadcast("Go!")
            mc.cancelTask(id)
        else
            mc.broadcast(remaining .. "...")
        end
    end)
end)
```

See the [mc.\* API](/reference/mc-api) for scheduler options.

## 5. Coroutine delays with mc.sleep

Command handlers and `mc.schedule` callbacks support `mc.sleep` directly.

`mc.sleep(ticks)` pauses execution for the given number of ticks. See [Async API](/reference/async-api) for details:

```lua
register("delayedheal", function(ctx)
    ctx.player:sendMessage("Healing in 3...")
    mc.sleep(20)
    ctx.player:sendMessage("2...")
    mc.sleep(20)
    ctx.player:sendMessage("1...")
    mc.sleep(20)
    ctx.player:heal(20)
    ctx.player:sendMessage("Healed!")
end)
```

## 6. Async HTTP with mc.fetch

`mc.fetch(url)` sends an HTTP GET request and waits for the response. See [Async API](/reference/async-api) for POST config and response fields:

```lua
register("playerinfo", function(ctx)
    local res = mc.fetch("https://api.github.com/users/" .. ctx.player.name)
    if res.ok then
        local data = res.json
        ctx.player:sendMessage("GitHub account: " .. (data.login or "not found"))
    else
        ctx.player:sendMessage("API error: " .. (res.error or res.status))
    end
end)
```

## 7. Permissions

Pass a permission node as the third argument to `register`. Players without it can't use the command:

```lua
register("kick <target:player> [<reason:text>]", function(ctx, target, reason)
    target:kick(reason or "Kicked by an operator")
end, "admin.kick")
```

## 8. Effects for flair

See [World API](/reference/world-api) and [Vector API](/reference/vector-api) for particle, sound, and Vec options:

```lua
register("fart", function(ctx)
    local p = ctx.player
    local pos = p.pos
    p.world:playSound("minecraft:entity.player.burp", pos.x, pos.y, pos.z, 1.0, 1.0)
    p.world:particle("minecraft:heart", Vec(pos.x, pos.y + 1, pos.z), {
        count = 5,
        delta = { 0.5, 0.5, 0.5 }
    })
    p:sendMessage("You farted!")
end)
```

## Next steps

- [Events and storage](/guide/02-events-and-storage) — part 2 of this guide: reacting to events and saving data
- [Events](/reference/events) — react to player joins, block breaks, chat, and more
- [Async API](/reference/async-api) — detailed mc.fetch and mc.sleep reference
- [Storage](/reference/storage) — persist data across reloads with `mc.data` and `player.data`
- [Libraries](/docs/libraries/overview) — formatting templates, simple registrations, chest GUIs
- [Language extensions](/reference/language) — `\{ ... }` lambda syntax
