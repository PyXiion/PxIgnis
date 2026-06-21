---
title: simple
description: Register commands with built-in formatting and broadcasting using registerSimple().
---

The `simple` library wraps `register()` and the `format` library into one concise function.

## Loading

```lua
local registerSimple = require "simple"
```

## registerSimple(cmd, template, range?, overlay?)

| Param | Type | Description |
|-------|------|-------------|
| `cmd` | `string` | Command syntax string |
| `template` | `string` | Format string passed to `format` |
| `range` | `number?` | If > 0, uses `world:broadcastInRange` instead of global broadcast |
| `overlay` | `bool\|number?` | If `true`, sends as title overlay. If a number, acts as both range and overlay flag |

Argument names are extracted automatically from the syntax string. The `p` variable is
automatically set to `ctx.player`.

## Examples

Broadcast a message when a player throws a fireball (global chat):

```lua
registerSimple("throw <target:player>",
    "*{p.name} throws a fireball at {t.name}*")
```

Broadcast in range (50 blocks):

```lua
registerSimple("yell <message:text>",
    "{p.name} yells: {m}", 50)
```

As a title overlay:

```lua
registerSimple("announce <message:text>",
    "{m}", true)
```

With range and overlay:

```lua
registerSimple("localmsg <message:text>",
    "{m}", 50, true)
```
