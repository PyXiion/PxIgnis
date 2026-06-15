---
title: format
description: F-string-like templating library for PxIgnis.
---

The `format` library provides string templating using `{expr}` placeholders with dot-notation access.

## Loading

```lua
local format = require "format"
```

## format(pattern)

Returns a function that accepts an arguments table and returns a formatted string.

```lua
local fmt = format("{p.name} has {count} apples")
local result = fmt({p = {name = "Alex"}, count = 3})
-- "Alex has 3 apples"
```

## broadcastFormat(pattern)

Returns a function that formats the string and broadcasts it to all players in one call.

```lua
local bf = broadcastFormat("*{p.name} throws a fireball at {t.name}*")
bf({p = ctx.player, t = target})
```

## Examples

```lua
local format = require "format"

register("shout <message:text>", function(ctx, message)
    local fmt = format("*{p.name} shouts: {msg}*")
    local text = fmt({p = ctx.player, msg = message})
    ctx.player:sendMessage(text)
end)
```

With broadcast:

```lua
local bf = broadcastFormat("*{p.name} throws a fireball at {t.name}*")

register("throw <target:player>", function(ctx)
    local target = ctx.args.target
    bf({p = ctx.player, t = target})
end)
```
