---
title: Registering Commands
description: Dynamic Brigadier command registration with Lua handlers — syntax, argument types, permissions.
---

Use `register()` to create dynamic Brigadier commands from Lua without touching Java code.

**Warning:** Commands can only be registered at script load time — you cannot add commands at runtime (e.g. inside event
handlers or scheduled callbacks). All `register()` calls must be at the top level of your `.lua` files.

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
command <name:type> [name:type] [name:choice=a,b]
```

| Part                | Meaning                              |
|---------------------|--------------------------------------|
| `cmd` `sub`         | Literal path tokens                  |
| `<name:type>`       | Required argument                    |
| `[name:type]`       | Optional trailing argument           |
| `<name:choice=a,b>` | Choice argument (one literal per option) |

Optional `[<name:type>]` is also supported, because i think both of them are pretty.

Optional arguments can only appear such that no required argument follows them. Literals
may appear after optional arguments. Omitting any optional arg removes all later ones too.

## Argument types

| Type         | Lua type  | Description                                                    |
|--------------|-----------|----------------------------------------------------------------|
| `text`       | `string`  | Greedy: consumes the rest of the command line.                 |
| `word`       | `string`  | Single word (`abc` or `"abc abc"`)                             |
| `player`     | `player`  | Player                                                         |
| `entity`     | `entity`  | Entity (selector)                                              |
| `int`        | `number`  | Integer (an integer in Lua, not a float)                       |
| `double`     | `number`  | Double-precision float                                         |
| `float`      | `number`  | Single-precision float                                         |
| `bool`       | `boolean` | Boolean                                                        |
| `block_pos`  | `Vector`  | Block position as a [Vector](/reference/vector-api)            |
| `choice=a,b` | `string`  | One of the listed words; each is a command literal (see below) |

### Choice

`<mode:choice=creative,survival>` registers one command literal per option, so parsing, error messages and tab
completion are exactly vanilla, and non-ASCII options (`choice=да,нет`) work. The handler receives the matched word.
An option must be a single word: an option with a space, an empty option or a duplicate is an error at `register`.

An argument name may be used only once in a syntax string; repeating it is an error at `register`.

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
If omitted, all players can use the command.

```lua
register("kick <target:player>", function(ctx, target)
    target:kick("Kicked by operator")
end, "admin.kick")
```

## Reserved commands

These cannot be registered (for safety):

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
