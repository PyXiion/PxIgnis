# PxIgnis

![version](https://img.shields.io/badge/version-0.15.0-purple)
![version](https://img.shields.io/badge/MC-1.21.10-green)
![version](https://img.shields.io/badge/MC-1.21.11-green)

Lua scripting for Minecraft Fabric servers. Custom commands, event hooks,
persistent state, and the full MC APIs.

> This project is developed with the assistance of AI. Humans were harmed
> (and included) during development too.

[Docs](https://ignis.pyxiion.ru)

---

## About

PxIgnis is developed in tandem with a fully Vanilla custom Roguelike server.
The API reflects what the project itself needed.

---

## What's inside

| Area          | What you get                                                                           |
|---------------|----------------------------------------------------------------------------------------|
| **Commands**  | Simple command registration withot boilerplaye                                         |
| **Events**    | Players, entities, blocks, items, server lifecycle                                     |
| **Reload**    | `/ignis reload` re-executes all scripts; persistent state via `mc.data` / `player.data` |
| **MC API**    | Particles, sounds, blocks, entities, NBT, structures, weather, world border, explosions |
| **UI**        | Per-player sidebar, chest GUI lib, boss bars, holograms, titles                        |
| **Async**     | `mc.fetch()` for HTTP, `mc.sleep()` for coroutine delays                               |
| **Storage**   | Per-player and global tables                                                           |
| **Dev tools** | LuaLS types for IntelliSense, metatable extension hooks                                |

---

## Examples

### 1. Region + async

```lua
local arena = world:createRegion(vec(-50, 64, -50), vec(50, 80, 50))

arena:on("entity_enter", function(entity)
    if entity:isPlayer() then
        mc.broadcast(entity.name .. " entered the arena")

        coroutine.wrap(function()
            local res = mc.fetch("https://api.example.com/arena/log")
            if not res.ok then
                mc.broadcast("log failed: " .. res.error, true)
            end
        end)()
    end
end)
```

### 2. Mob AI

```lua
mc.registerBehaviour("zombie_chase", function(self, mob)
    local p = mc.players[1]
    if p then
        local pos = p.pos
        mob:navigateTo(pos.x, pos.y, pos.z, 1.0)
    end
end)

mc.on("entity_spawn", function(entity)
    if entity.isMob and entity.type == "minecraft:zombie" then
        entity:setAI("zombie_chase")
    end
end)
```

### 3. Command with permission

```lua
register("pay <amount:int> <target:player>", function(ctx, amount, target)
    ctx.player.data.balance = (ctx.player.data.balance or 0) - amount
    target.data.balance     = (target.data.balance or 0) + amount

    target:sendMessage("Received " .. amount .. " from " .. ctx.player.name)
end, "pxrp.economy")
```

---

## Install

Minecraft 1.21.x · Fabric Loader ≥0.19.2 · Fabric API ≥0.141.4 · Fabric Language Kotlin ≥1.10.8

1. Place `pxignis-*.jar` into the `mods/` folder.
2. Start the server — `config/ignis/demo.lua` is created.
3. Edit scripts under `config/ignis/`, then run `/ignis reload`.

## License

GNU Lesser General Public License v3.0
