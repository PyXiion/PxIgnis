# PxRP

> [!NOTE]
> Beware that this project is also coded by AI. Humans included too.

![version](https://img.shields.io/badge/version-0.2.2-blue)

A Lua-scriptable roleplay command framework for Minecraft Fabric servers. Define custom chat commands using Lua scripts — no Java or Kotlin mod code required.

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks.
- **Dynamic reload** — `/pxrp reload` re-executes all Lua scripts without restarting the server.
- **Built-in argument types** — `text` (free-form message) and `target` (player selector with tab completion).
- **Minecraft API exposed to Lua** — particles, sounds, global and range-limited broadcasting.
- **Persistent data storage** — Key-value data per player (`ctx.player.data`) and globally (`mc.data`), auto-persisted to JSON.
- **Permission system** — Uses the Fabric Permissions API (supports both OP-based and permissions-plugin-based systems).
- **Player context** — Command handlers receive the sender's name, position, direction, and world.
- **Lua libraries** — Bundled `format.lua` (f-string-like templating) and `simple.lua` (concise command registration).

## Requirements

- Minecraft 1.21.x
- Fabric Loader ≥0.19.2
- Fabric API ≥0.141.4
- Fabric Language Kotlin ≥1.10.8

## Quick start

1. Install the mod on your Fabric server.
2. On first run, `config/pxrp.lua` is created with example commands.
3. Run `/pxrp reload` (requires operator level 4 or `pyxiion.pxrp` permission) to apply changes.

## Examples

### Basic command

```lua
register("fart", {}, function(ctx)
    local player = ctx.player
    local pos = player.pos
    local dir = player.bodyDir

    broadcastFormat "*{p.name} farted*" {p = player}
    mc.particle("minecraft:gust", pos.x - dir.x * 0.5, pos.y + 0.6, pos.z - dir.z * 0.5, player.world)
    mc.playSound("minecraft:entity.slime.squish", pos.x, pos.y, pos.z, player.world, 10, 0.1)
end)
```

### Arguments

Argument types can be given custom names with the `name:type` syntax:

```lua
register("rp kill", {"target"}, function(ctx, target)
    broadcastFormat "*{p.name} killed {t.name}*" {p = ctx.player, t = target}
end, "rp.kill")

-- Custom arg names are useful when you have multiple args of the same type:
register("try", {"action:text"}, function(ctx, action)
    mc.broadcast(ctx.player.name .. " tries to " .. action)
end)
```

### Persistent player data

Every player has a `ctx.player.data` Lua table that persists automatically to disk.

```lua
register("coins", {}, function(ctx)
    local bal = ctx.player.data.coins or 0
    mc.broadcast("You have " .. bal .. " coins")
end)

register("rp coins give", {}, function(ctx)
    ctx.player.data.coins = (ctx.player.data.coins or 0) + 10
    mc.broadcast("+10 coins! Total: " .. ctx.player.data.coins)
end)
```

### Cross-player data

Data tables are shared — modifying another player's data from a command works:

```lua
register("rp pay", {"target"}, function(ctx, target)
    local bal = ctx.player.data.coins or 0
    if bal < 10 then
        mc.broadcast("Not enough coins! You have " .. bal)
        return
    end
    ctx.player.data.coins = bal - 10
    target.data.coins = (target.data.coins or 0) + 10
    mc.broadcast(ctx.player.name .. " paid 10 coins to " .. target.name)
end)
```

### Global server data

`mc.data` is a server-wide persistent table shared by all players.

```lua
register("rp event", {}, function()
    local total = (mc.data.totalEvents or 0) + 1
    mc.data.totalEvents = total
    mc.broadcast("Server event #" .. total .. " started!")
end)
```

### Bundled Lua libraries

The mod ships with two Lua libraries loaded via `require`:

```lua
require "format"    -- provides format() and broadcastFormat()
require "simple"    -- provides registerSimple()
```

Include these at the top of your `pxrp.lua` (or any script) to use them.

#### `format.lua` — template engine

Templates use `{expr}` placeholders with dot-notation access:

```lua
format(pattern)(args)              -- returns formatted string
broadcastFormat(pattern)(args)     -- formats and broadcasts in one call
```

```lua
broadcastFormat "*{p.name} throws a fireball at {t.name}*" {p = ctx.player, t = target}
```

#### `simple.lua` — concise command registration

`registerSimple` creates a command that formats and broadcasts a template, passing `p = ctx.player` automatically:

```lua
registerSimple(cmd, args, template, range?, overlay?)
```

| Param | Type | Description |
|-------|------|-------------|
| `cmd` | `string` | Command path |
| `args` | `table` | Argument type list (same as `register`) |
| `template` | `string` | Format template (`{p.name}`, `{argName}`, etc.) |
| `range` | `number?` | If > 0, uses `broadcastInRange` with this radius |
| `overlay` | `boolean\|number?` | Send as title overlay: `true` = 7s, or a custom tick count |

```lua
-- Global broadcast
registerSimple("wave", {}, "*{p.name} waves at everyone*", 15)    -- range 15 blocks
registerSimple("bow", {}, "*{p.name} bows*", nil, true)           -- title overlay
registerSimple("cheer", {}, "*{p.name} cheers*", 20, 60)          -- both
```

### Built-in Lua standard libraries

PxRP loads the following Lua standard libraries via [`luaj`](https://github.com/luaj/luaj) (targeting Lua 5.1):

| Library | Globals | Reference |
|---------|---------|-----------|
| **Base** | `type`, `tostring`, `tonumber`, `pairs`, `ipairs`, `pcall`, `error`, `assert`, `select`, `unpack`, `_G`, etc. | [§2–6](https://www.lua.org/manual/5.1/manual.html#2) |
| **math** | `math.random`, `math.randomseed`, `math.floor`, `math.ceil`, `math.sin`, `math.cos`, `math.sqrt`, `math.min`, `math.max`, `math.pi`, `math.huge` | [§5.6](https://www.lua.org/manual/5.1/manual.html#5.6) |
| **string** | `string.format`, `string.sub`, `string.find`, `string.match`, `string.gmatch`, `string.gsub`, `string.len`, `string.byte`, `string.char`, `string.rep`, `string.lower`, `string.upper` | [§5.4](https://www.lua.org/manual/5.1/manual.html#5.4) |
| **table** | `table.insert`, `table.remove`, `table.sort`, `table.concat`, `table.maxn` | [§5.5](https://www.lua.org/manual/5.1/manual.html#5.5) |
| **bit32** | `bit32.band`, `bit32.bor`, `bit32.bxor`, `bit32.lshift`, `bit32.rshift`, `bit32.arshift`, `bit32.bnot` | [luaj wiki](https://github.com/luaj/luaj) |
| **package** | `require`, `package.path`, `package.loaded`, `package.preload` | [§5.3](https://www.lua.org/manual/5.1/manual.html#5.3) |

The following standard libraries are **not** loaded: `io`, `os`, `coroutine`, `debug`.

See the complete [Lua 5.1 Reference Manual](https://www.lua.org/manual/5.1/) for detailed documentation.

## Lua API

### `register(path, arguments, handler, permission?)`

Registers a Brigadier command.

- `path` — Command path string, e.g. `"rp kill"`.
- `arguments` — Table of argument types, e.g. `{"target", "text"}`. Use `name:type` syntax for custom names (e.g. `"action:text"`), otherwise names are auto-generated.
- `handler` — Lua function called when the command executes. The first argument is always a Context object (`ctx`); subsequent arguments are the parsed command arguments.
- `permission` — Optional permission node string.

### Context object

Every command handler receives a **Context** object as its first argument. The context exposes the executing player:

| Field | Type | Description |
|---|---|---|
| `ctx.player` | `Player` | The player who executed the command |

### Player object

The player object is accessed via `ctx.player` inside a command handler:

| Field | Type | Description |
|---|---|---|
| `ctx.player.name` | `string` | Player display name |
| `ctx.player.pos` | `{x, y, z}` | Position vector |
| `ctx.player.dir` | `{x, y, z}` | Look direction vector |
| `ctx.player.bodyDir` | `{x, y, z}` | Body yaw direction vector |
| `ctx.player.world` | `string` | World key (e.g. `minecraft:overworld`) |
| `ctx.player.data` | `table` | Persistent key-value storage |

### `ctx.player.data` — Persistent per-player storage

A Lua table that persists to disk (<config>/pxrp/storage/players/<uuid>.json). Data is written to disk when:
- The server stops
- A player disconnects
- `/pxrp reload` or F3+T is executed

This batching approach improves performance for scripts that make multiple data assignments.

```lua
ctx.player.data.coins = (ctx.player.data.coins or 0) + 1          -- queued for write
ctx.player.data.inventory = {sword = 1, shield = 1}               -- queued for write
ctx.player.data.nested.key = "value"                               -- ❌ will NOT save
-- Fix:
local t = ctx.player.data.nested or {}
t.key = "value"
ctx.player.data.nested = t                                         -- ✅ queued for write
```

### `mc.data` — Persistent global storage

A server-wide persistent table (<config>/pxrp/storage/global.json). Data is written to disk when:
- The server stops
- A player disconnects
- `/pxrp reload` is executed

```lua
mc.data.eventActive = true
mc.data.totalPlayers = (mc.data.totalPlayers or 0) + 1
```

### `mc.particle(id, x, y, z, world)`

Spawns a particle at the given coordinates for all online players.

### `mc.playSound(id, x, y, z, world, volume?, pitch?)`

Plays a sound at the given coordinates.

### `mc.broadcast(text, overlay?)`

Sends a chat message to all players. If `overlay` is a number, sends a title overlay for that many ticks.

### `mc.broadcastInRange(text, x, y, z, range, world, overlay?)`

Sends a message only to players within the given radius in the given world.

### `mc.time()`

Returns the current server epoch time in seconds (as a double). Useful for cooldowns:

```lua
local last = ctx.player.data.lastFart or 0
if mc.time() - last < 10 then
    mc.broadcast("Wait " .. (10 - (mc.time() - last)) .. " seconds!")
    return
end
ctx.player.data.lastFart = mc.time()
```

## Storage

Data is stored as JSON in `config/pxrp/storage/`:

- `config/pxrp/storage/global.json` — Global data
- `config/pxrp/storage/players/<uuid>.json` — Per-player data

The storage backend is abstract (`DataBackend` interface). Currently ships with `JsonBackend`. The interface allows adding SQLite or PostgreSQL backends later without changing Lua code.

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](LICENSE.txt).
