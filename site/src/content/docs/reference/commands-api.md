---
title: Registering Commands
description: Dynamic Brigadier command registration with Lua handlers — syntax, argument types, permissions.
---

Use `register()` to create dynamic Brigadier commands from Lua without touching Java code.

**Warning:** Commands can only be registered at script load time — you cannot add commands at runtime (e.g. inside event handlers or scheduled callbacks). All `register()` calls must be at the top level of your `.lua` files.

## Signature

```lua
register(syntax, handler, permission?)
```

| Param         | Type       | Description                                                |
|---------------|------------|------------------------------------------------------------|
| `syntax`      | `string`   | Command syntax string                                      |
| `handler`     | `function` | Callback invoked when the command runs                     |
| `permission?` | `string`   | Optional permission node (see [Permissions](#permissions)) |

## Syntax format

```
command <name:type> [<name:type>] [<name:choice=a,b>]
```

| Part                | Meaning                              |
|---------------------|--------------------------------------|
| `cmd` `sub`         | Literal path tokens                  |
| `<name:type>`       | Required argument                    |
| `[<name:type>]`     | Optional trailing argument           |
| `<name:choice=a,b>` | Choice argument with tab completions |

Optional arguments can only appear at the end of the syntax. Everything from the first `[...]` to the end is optional —
omitting any optional arg removes all later ones too.

## Argument types

| Type         | Lua type  | Description                                                    |
|--------------|-----------|----------------------------------------------------------------|
| `text`       | `string`  | Greedy: consumes the rest of the command line. **Must be last.** |
| `word`       | `string`  | Single word (`abc` or `"abc abc"`)                             |
| `player`     | `player`  | Player                                                         |
| `target`     | `player`  | Alias for `player`                                             |
| `int`        | `number`  | Integer                                                        |
| `double`     | `number`  | Double-precision float                                         |
| `float`      | `number`  | Single-precision float                                         |
| `bool`       | `boolean` | Boolean                                                        |
| `block_pos`  | `table`   | `{x, y, z}` block position table                               |
| `choice=a,b` | `string`  | Multi-choice — runtime validation + tab completion             |

## Handler arguments

The handler receives `ctx` as the first argument, followed by each argument value as a positional parameter in syntax
order.

```lua
register("warn <target:player> <reason:text>", function(ctx, target, reason)
    target:sendMessage("Warning: " .. reason)
end)
```

### Context object

| Property     | Type     | Description                   |
|--------------|----------|-------------------------------|
| `ctx.player` | `player` | Player who called the command |

## Permissions

Pass a permission node as the third argument. Players won't even see the command.

```lua
register("kick <target:player>", function(ctx, target)
    target:kick("Kicked by operator")
end, "admin.kick")
```

## Reserved commands

These cannot be registered:

`ignis`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`,
`whitelist`

## Examples

```lua
-- Simple command, no arguments, no permission
register("healme", function(ctx)
    ctx.player:heal(20)
    ctx.player:sendMessage("Healed!")
end)

-- Player argument with permission
register("fly <target:player>", function(ctx, target)
    target:setFlySpeed(0.1)
end, "myplugin.fly")

-- Optional argument
register("balance [<target:player>]", function(ctx, target)
    local t = target or ctx.player
    t:sendMessage("Balance: " .. (t.data.balance or 0))
end)

-- Choice argument
register("time set <phase:choice=day,night,noon,midnight>", function(ctx, phase)
    local times = { day = 1000, night = 13000, noon = 6000, midnight = 18000 }
    ctx.player.world.time = times[phase]
end)
```
