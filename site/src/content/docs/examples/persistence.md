---
title: Persistence
description: Complete examples using persistent data storage.
---

## 1. Coins Economy

```lua
register("pay <target:player> <amount:int>", function(ctx)
    local target = ctx.args.target
    local amount = ctx.args.amount

    local senderData = ctx.player.data
    local targetData = target.data

    senderData.balance = (senderData.balance or 0) - amount
    targetData.balance = (targetData.balance or 0) + amount

    ctx.player:sendMessage("You paid " .. amount .. " coins to " .. target.name)
    target:sendMessage("You received " .. amount .. " coins from " .. ctx.player.name)
end)

register("balance [<target:player>]", function(ctx)
    local target = ctx.args.target or ctx.player
    local bal = target.data.balance or 0
    ctx.player:sendMessage(target.name .. " has " .. bal .. " coins")
end)
```

## 2. Cooldown System

```lua
local cooldowns = {}

register("daily", function(ctx)
    local p = ctx.player
    local now = mc.time()
    local last = p.data.lastDaily or 0
    local cooldown = 86400 -- 24 hours in seconds

    if now - last < cooldown then
        local remaining = cooldown - (now - last)
        p:sendMessage("Wait " .. math.ceil(remaining) .. " more seconds!")
        return
    end

    p.data.lastDaily = now
    p.data.balance = (p.data.balance or 0) + 100
    p:sendMessage("You claimed your daily reward of 100 coins!")
end)
```

## 3. Ban System

```lua
register("ban <target:player> [<reason:text>]", function(ctx)
    local target = ctx.args.target
    local reason = ctx.args.reason or "Banned by an operator"

    local bans = mc.data.bans or {}
    bans[target.uuid] = {
        name = target.name,
        reason = reason,
        bannedBy = ctx.player.name,
        time = mc.time()
    }
    mc.data.bans = bans

    target:kick("Banned: " .. reason)
end, "px.ignis.ban")

mc.on("player_join", function(player)
    local bans = mc.data.bans or {}
    local ban = bans[player.uuid]
    if ban then
        player:kick("You are banned: " .. ban.reason)
        return false
    end
end)
```
