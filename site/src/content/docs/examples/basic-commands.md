---
title: Basic Commands
description: Complete examples of common command patterns.
---

## 1. Fart Command

```lua
local heart = particle("heart")

register("fart", function(ctx)
    local p = ctx.player
    local pos = p.pos
    p.world:playSound("minecraft:entity.player.burp", pos, 1.0, 1.0)
    p.world:emit(heart, vec(pos.x, pos.y + 1, pos.z), { count = 5, spread = 0.5 })
    p:sendMessage("You farted!")
end)
```

## 2. RP Kill Command

```lua
register("rp kill <target:player>", function(ctx, target)
    local fmt = require "format"
    local bf = broadcastFormat("*{p.name} kills {t.name}*")
    bf({p = ctx.player, t = target})
    target:damage(1000)
end, "px.ignis.rp")
```

## 3. Gamemode Command

```lua
register("gamemode <mode:choice=survival,creative,adventure,spectator> [<target:player>]", function(ctx, mode, target)
    local t = target or ctx.player
    t.gamemode = mode
    t:sendMessage("Setting gamemode to " .. mode)
end)
```

## 4. Kick Command

```lua
register("kick <target:player> [<reason:text>]", function(ctx, target, reason)
    local msg = reason or "You have been kicked"
    target:kick(msg)
end, "px.ignis.kick")
```
