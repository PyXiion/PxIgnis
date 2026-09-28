---
title: Getting Started
description: Install PxIgnis and write your first Lua script.
---

PxIgnis is a Fabric mod that embeds a hot-swappable Lua runtime into your Minecraft server.

## Requirements

- Minecraft 1.21.x
- Fabric Loader ≥0.19.2
- Fabric API ≥0.141.4
- Fabric Language Kotlin ≥1.10.8

## Installation

1. Install PxIgnis on your Fabric server.
2. On first run, `config/ignis/demo.lua` is created with example scripts.
3. Run `/ignis reload` (requires operator level 4 or `px.ignis` permission) to apply changes.

## Your First Script

Create `config/ignis/hello.lua`:

```lua
register("hello", function(ctx)
    ctx.player:sendMessage("Hello, " .. ctx.player.name .. "!")
end)
```

Run `/ignis reload`, then type `/hello` in chat.

## Command Structure

```lua
register("cmd <name:type> [<name:type>]", handler, permission?)
```

| Part                | Meaning                          |
|---------------------|----------------------------------|
| `cmd` `sub`         | Literal path tokens              |
| `<name:type>`       | Required argument                |
| `[<name:type>]`     | Optional trailing argument       |
| `<name:choice=x,y>` | Choice type with tab completions |

P.S. `[name:type]` is equal to `[<name:type>]` =D

## Configuration

All Lua scripts go in `config/ignis/`. Files are loaded alphabetically.

## Editor setup

On start PxIgnis turns `config/ignis/` into a workspace for the
[Lua Language Server](https://luals.github.io/): it unpacks type definitions of the whole API into
`config/ignis/.types/` (refreshed on every start, so they match the installed version) and creates
`config/ignis/.luarc.json` once (edit it freely; it is not overwritten).

Open the `config/ignis` folder in VS Code with the **Lua** extension (by sumneko), or any editor with LuaLS, and you
get completion for `mc`, `vec`, `register`, wrapper methods and event fields (`mc.on("block.break", function(e)` knows
that `e.player` is a Player), plus warnings for typos.

## Development commands

All `/ignis` commands need operator level 4 or the `px.ignis` permission.

| Command                   | What it does                                                                                           |
|---------------------------|--------------------------------------------------------------------------------------------------------|
| `/ignis reload`           | Reloads all scripts. If a script has a syntax error, nothing is replaced and the old scripts keep running |
| `/ignis watch on`         | Reloads automatically whenever a `.lua` file in `config/ignis/` is saved; the result goes to chat. `/ignis watch off` stops it. Start the server with `-Dpxignis.watch=true` to have it on from the start |
| `/ignis eval <lua>`       | Runs Lua in the scripts' environment and prints the result, e.g. `/ignis eval me.pos` or `/ignis eval #mc.players`. `me` is you. Needs `px.ignis.eval` (level 4 by default) |
| `/ignis status`           | Loaded scripts, event handlers per event, registered commands, scheduled tasks and regions             |

## Script errors in chat

Errors in event handlers, commands and scheduled tasks, and warnings such as an unknown event name, are written to
the server log and also sent to online players with the `px.ignis.prompt_errors` permission (operators level 4 by
default), in the form `event 'block.break': shop.lua:12: attempt to index a nil value`. The same error is repeated in
chat at most once every 10 seconds, so a handler failing every tick does not flood it.

## Next steps

- [1. Your first command](/guide/01-your-first-command) and [2. Events and storage](/guide/02-events-and-storage):
  short recipes for the basics.
- [Tutorial: an arena minigame](/tutorial): build a complete minigame step by step.
