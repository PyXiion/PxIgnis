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

| Part | Meaning |
|------|---------|
| `cmd` `sub` | Literal path tokens |
| `<name:type>` | Required argument |
| `[<name:type>]` | Optional trailing argument |
| `<name:choice=x,y>` | Choice type with tab completions |

## Configuration

All Lua scripts go in `config/ignis/`. Files are loaded alphabetically.

`package.path`: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`
