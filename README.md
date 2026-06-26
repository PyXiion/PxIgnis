# PxIgnis

![version](https://img.shields.io/badge/version-0.16.1-purple)
![version](https://img.shields.io/badge/MC-1.21.10-green)
![version](https://img.shields.io/badge/MC-1.21.11-green)

Lua scripting for Minecraft Fabric servers.

> This project is developed with the assistance of AI. Humans were harmed
> (and included) during development too.

[Full Docs](https://ignis.pyxiion.ru) · [Полная документация](https://ignis.pyxiion.ru/?lang=ru) · [Quick start](https://ignis.pyxiion.ru/guide/getting-started) · [Changelog](site/src/content/docs/changelog.md)

---

### Examples

```lua
-- config/ignis/greet.lua
register("greet <name:text>", function(ctx, name)
    ctx.player:sendMessage("Hello, " .. name .. "!")
end)
```

```lua
mc.on("player_join", function(player)
    player.data.joins = (player.data.joins or 0) + 1
    player:sendMessage("Welcome back! (#" .. player.data.joins .. ")")
end)
```

```lua
mc.on("player_block_break", function(player, pos, blockId)
    if blockId == "minecraft:diamond_ore" then
        player:sendTitle({ title = "Lucky!" })
        player.world:particle("minecraft:totem_of_undying", pos)
    end
end)
```

```lua
-- config/ignis/shop.lua
local chestgui = require "chestgui"

register("shop", function(ctx)
    if not ctx.player:hasPermission("px.ignis.shop") then
        ctx.player:sendMessage("No permission.")
        return
    end

    ctx.player.sidebar = {
        title = "Shop",
        lines = { "1. Diamonds", "2. Emeralds" },
    }

    local gui = chestgui.create(3, "Shop")
    gui:fill(mc.createItem("gray_stained_glass_pane"))
    gui:set(2, 3, mc.createItem("diamond", 1), function(player, slot, clickType, slotItem, cursorItem)
        player:sendMessage("You bought a diamond!")
    end)
    gui:set(2, 4, mc.createItem("emerald", 1), function(player, slot, clickType, slotItem, cursorItem)
        player:sendMessage("You bought an emerald!")
    end)
    gui:open(ctx.player)
end, "px.ignis.shop")
```

---

## What's inside

| Area          | What you get                                                                                                               |
|---------------|----------------------------------------------------------------------------------------------------------------------------|
| **Commands**  | Brigadier with tab completion, permissions, and types (`text`, `word`, `player`, `int`, `bool`, `block_pos`, `choice=...`) |
| **Events**    | Players, entities, blocks, items, server lifecycle                                                                         |
| **Reload**    | `/ignis reload` re-executes all scripts; persistent state via `mc.data` / `player.data`                                    |
| **MC API**    | Particles, sounds, blocks, entities, NBT, structures, weather, world border, explosions                                    |
| **UI**        | Per-player sidebar, chest GUI lib, boss bars, holograms, titles                                                            |
| **Async**     | `mc.fetch()` for HTTP, `mc.sleep()` for coroutine delays                                                                   |
| **Storage**   | Per-player and global JSON-backed key-value tables                                                                         |
| **Dev tools** | LuaLS types for IntelliSense, `mc.dump()`, metatable extension hooks                                                       |

## Install

Minecraft 1.21.x · Fabric Loader ≥0.19.2 · Fabric API ≥0.141.4 · Fabric Language Kotlin ≥1.10.8

1. Drop the mod into `mods/`.
2. Start the server — `config/ignis/demo.lua` is created.
3. Edit scripts, run `/ignis reload` (OP-4 or `px.ignis`).

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](LICENSE.txt).
