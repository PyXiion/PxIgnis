# PxRP

A Lua-scriptable roleplay command framework for Minecraft Fabric servers. Define custom chat commands using Lua scripts — no Java or Kotlin mod code required.

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks.
- **Dynamic reload** — `/pxrp reload` or `/reload` re-executes all Lua scripts without restarting the server.
- **Built-in argument types** — `text` (free-form message) and `target` (player selector with tab completion).
- **Minecraft API exposed to Lua** — particles, sounds, global and range-limited broadcasting.
- **Permission system** — Uses the Fabric Permissions API (supports both OP-based and permissions-plugin-based systems).
- **Player context** — Command handlers receive the sender's name, position, direction, and world.
- **Shadowed dependencies** — LuaJ and Fabric Permissions API are shaded into the jar to avoid classpath conflicts.

## Requirements

- Minecraft 1.21.11
- Fabric Loader ≥0.19.2
- Fabric API ≥0.141.4
- Fabric Language Kotlin ≥1.10.8

## Usage

1. Install the mod on your Fabric server.
2. On first run, a default `config/pxrp.lua` is created. Edit it to define your commands.
3. Run `/pxrp reload` (requires operator level 4 or `pyxiion.pxrp` permission) to apply changes.

### Example Lua command

```lua
register("fart", {}, function()
    local pos = player.pos
    mc.particle("minecraft:campfire_cosmoke", pos.x, pos.y + 1, pos.z, player.world)
    mc.playSound("minecraft:entity_player_burp", pos.x, pos.y, pos.z, player.world)
    mc.broadcast(player.name .. " farted!")
end)
```

### Argument example

```lua
register("rp", {"target", "text"}, function()
    mc.broadcastInRange("* " .. player.name .. " kills " .. args.target.name .. " with " .. args.text .. " *",
        player.pos.x, player.pos.y, player.pos.z, 15, player.world)
end)
```

### Higher-level API

The bundled `simple.lua` provides `registerSimple` for concise command registration:

```lua
registerSimple("wave", {}, "{player.name} waves at everyone!", 15)
```

## Lua API

### `register(path, arguments, handler, permission?)`

- `path` — Command path string (e.g. `"rp kill"`).
- `arguments` — Table of argument type names (e.g. `{"target", "text"}`).
- `handler` — Lua function called when the command executes.
- `permission` — Optional permission node string.

### `mc.particle(id, x, y, z, world)`

Spawns a particle at the given coordinates for all online players.

### `mc.playSound(id, x, y, z, world, volume?, pitch?)`

Plays a sound at the given coordinates.

### `mc.broadcast(text, overlay?)`

Sends a chat message to all players. If `overlay` is a number, it sends a title overlay for that many ticks.

### `mc.broadcastInRange(text, x, y, z, range, world, overlay?)`

Sends a message to players within the specified radius.

### Player object

Inside a command handler, the `player` global provides:
- `player.name` — Player name
- `player.pos` — `{x, y, z}` position
- `player.dir` — Look direction vector
- `player.bodyDir` — Body yaw direction vector
- `player.world` — Dimension key (e.g. `minecraft:overworld`)

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](LICENSE.txt).
