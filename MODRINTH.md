# PxRP

**Lua-scriptable roleplay command framework for Minecraft Fabric servers.** Define custom chat commands and server logic using Lua scripts — no Java or Kotlin mod code required.

[⚙️ GitHub & Documentation](https://github.com/PyXiion/PxRP)

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, optional args, and permission checks.
- **Event system** — React to player joins, leaves, deaths, chat messages, and server lifecycle with Lua handlers (join/chat events are cancellable).
- **Dynamic reload** — Use `/pxrp reload` to instantly re-execute all Lua scripts without restarting the server.
- **Rich argument types** — Supports `player`/`target`, `text`, `int`, `double`, `float`, `bool`, `block_pos`, and `choice=a,b,c` with parse-time validation and tab completion.
- **Minecraft API exposed to Lua** — Trigger particles, sounds, broadcasting (global and range-limited), block manipulation (set/get/fill), and server time control.
- **Scheduler** — Built-in `mc.schedule()` and `mc.scheduleRepeating()` for delayed or repeating tasks measured in ticks.
- **Persistent storage** — Per-player (`ctx.player.data`) and global (`mc.data`) key-value data automatically persisted to JSON.
- **Full Player API** — Live entity wrapper with readable properties (pos, health, food, gamemode, ping, xp, etc.) and methods (sendMessage, teleport, kick, playSound, give, damage, heal, clear).
- **Permission system** — Integrates seamlessly with the Fabric Permissions API (works with LuckPerms, OP-based systems, etc.).
- **Reserved command protection** — 13 critical server commands cannot be accidentally shadowed or broken by scripts.

## Quick Start

1. Install PxRP on your Fabric server along with its dependencies.
2. On the first run, a `config/pxrp/demo.lua` file will be created with example scripts.
3. Edit the scripts and run `/pxrp reload` (requires OP level 4 or `pyxiion.pxrp` permission) to apply changes!

## Examples

### Creating a Command

```lua
register("fart", {}, function(ctx)
    local player = ctx.player
    local pos = player.pos
    broadcastFormat "*{p.name} farted*" {p = player}
    player.world:particle("minecraft:gust", pos.x, pos.y + 0.6, pos.z)
    mc.playSound("minecraft:entity.slime.squish", pos.x, pos.y, pos.z, player.world.name, 10, 0.1)
end)
```

### Event handling

```lua
mc.on("player_join", function(player)
    mc.broadcast("Welcome, " .. player.name .. "!")
end)

mc.on("player_death", function(player, damageType)
    mc.broadcast(player.name .. " died to " .. damageType)
end)
```

### Arguments & permissions
```lua
register("gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]", function(ctx, mode, target)
    local p = target or ctx.player
    p.gamemode = mode
    mc.broadcast(p.name .. " is now in " .. mode .. " mode")
end, "pxrp.admin")
```

## License
This project is licensed under the GNU Lesser General Public License v3.0.