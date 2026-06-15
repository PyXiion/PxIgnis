---
title: Basic Commands
description: Complete examples of common command patterns.
---

## 1. Fart Command

```lua
register("fart", function(ctx)
    local p = ctx.player
    local pos = p.pos
    p.world:playSound("entity_player_burp", pos.x, pos.y, pos.z, 1.0, 1.0)
    p.world:particle("minecraft:heart", Vec(pos.x, pos.y + 1, pos.z), {count = 5, spread = Vec(0.5, 0.5, 0.5)})
    p:sendMessage("You farted!")
end)
```

## 2. RP Kill Command

```lua
register("rp kill <target:player>", function(ctx)
    local target = ctx.args.target
    local fmt = require "format"
    local bf = broadcastFormat("*{p.name} kills {t.name}*")
    bf({p = ctx.player, t = target})
    target:damage(1000)
end, "px.ignis.rp")
```

## 3. Gamemode Command

```lua
register("gamemode <mode:choice=survival,creative,adventure,spectator> [<target:player>]", function(ctx)
    local target = ctx.args.target or ctx.player
    local modes = {survival = 0, creative = 1, adventure = 2, spectator = 3}
    target:sendMessage("Setting gamemode to " .. ctx.args.mode)
    target:sendMessage("Your gamemode has been changed")
end)
```

## 4. Kick Command

```lua
register("kick <target:player> [<reason:text>]", function(ctx)
    local reason = ctx.args.reason or "You have been kicked"
    ctx.args.target:kick(reason)
end, "px.ignis.kick")
```
