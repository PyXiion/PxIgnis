# PxRP

**Lua-scriptable roleplay command framework for Minecraft Fabric servers.** Define custom chat commands using Lua scripts — no Java or Kotlin mod code required.

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks
- **Dynamic reload** — `/pxrp reload` (or F3+T) re-executes all Lua scripts without restarting the server
- **Minecraft API exposed to Lua** — particles, sounds, broadcasting, time
- **Persistent data storage** — Key-value data per player and globally, auto-persisted to JSON
- **Permission system** — Uses Fabric Permissions API (OP-based and plugin-based)
- **Bundled Lua libs** — `format.lua` (f-string templating) and `simple.lua` (concise registration)

## Requirements

- Minecraft 1.21.x
- Fabric Loader ≥0.19.2
- Fabric API ≥0.141.4
- Fabric Language Kotlin ≥1.10.8

## Quick start

1. Install on your Fabric server
2. On first run, `config/pxrp.lua` is created with example commands
3. Run `/pxrp reload` (requires op level 4 or `pyxiion.pxrp` permission) to apply changes

## Example

```lua
register("fart", {}, function(ctx)
    local player = ctx.player
    local pos = player.pos
    broadcastFormat "*{p.name} farted*" {p = player}
    mc.particle("minecraft:gust", pos.x, pos.y + 0.6, pos.z, player.world)
    mc.playSound("minecraft:entity.slime.squish", pos.x, pos.y, pos.z, player.world, 10, 0.1)
end)
```

See the [full documentation on GitHub](https://github.com/PyXiion/PxRP) for the complete API reference and more examples.

## License

GNU Lesser General Public License v3.0.
