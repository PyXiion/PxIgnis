# PxIgnis

A Lua runtime for Fabric servers, with hot-reload. Edit scripts in `config/ignis/`,
run `/ignis reload`, and changes apply instantly.

Full documentation at **[ignis.pyxiion.ru](https://ignis.pyxiion.ru)**.

---

## About

PxIgnis is developed in tandem with a fully Vanilla custom Roguelike server. The API
reflects what the project itself needed.

---

## Key Features

* **Hot-reload Lua runtime** — `/ignis reload` re-runs scripts in milliseconds. Commands
  are re-registered into the live Brigadier dispatcher without restarting the server.
* **Command registration** — `register("cmd <name:type> [<name:type>]", handler, permission?)`.
  Built-in argument types: `int`, `double`, `float`, `bool`, `text`, `word`, `player`,
  `block_pos`, `choice=...`. Tab completion and permissions (`admin.cmd`) are part
  of the API.
* **Events, with cancellation** — 15+ hooks: `player_block_break`, `player_chat`,
  `player_attack_entity`, `entity_hurt`, `entity_death`, and more. Returning `false`
  cancels.
* **Regions and scripted mob AI** — spatial areas with enter/exit/move/death
  events, and API for custom mob AI.
* **Coroutines, persistence, UI** — `mc.fetch` / `mc.sleep` for sequential async code,
  `player.data` / `mc.data` / storage backends for persistent state, plus holograms,
  sidebars, and the `chestgui` library for custom containers.

And more, and more.

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
    if entity:isMob() and entity.type == "minecraft:zombie" then
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

## Installation & Requirements

1. Install Fabric Loader ≥0.19.2, Fabric API ≥0.141.4+1.21.11, and Fabric Language Kotlin ≥1.10.8.
2. Place `pxignis-*.jar` into the `mods/` folder of your server.
3. Start the server. `config/ignis/demo.lua` is created on first boot.
4. Edit scripts under `config/ignis/`, then run `/ignis reload`.


## License

GNU Lesser General Public License v3.0