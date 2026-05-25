# PxRP

![version](https://img.shields.io/badge/version-0.3.0-blue)

A Lua-scriptable roleplay command framework for Minecraft Fabric servers. Define custom chat commands and complex server logic using Lua scripts — no Java or Kotlin mod code required.

> This project is developed with the assistance of AI. Humans were harmed (and included) during development too.

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks.
- **Event system** — React to player joins, leaves, deaths, chat messages, and server lifecycle with Lua handlers (join/chat events are cancellable).
- **Dynamic reload** — `/pxrp reload` re-executes all Lua scripts instantly without restarting the server.
- **Rich argument types** — Supports `text`, `word`, `target`/`player`, `int`, `double`, `float`, `bool`, `block_pos`, and custom choices (`choice=a,b,c`) with validation.
- **Minecraft API exposed to Lua** — Trigger particles, sounds, global/range broadcasting, block manipulation, entity spawning, world time/weather control, and server time access.
- **Persistent data storage** — Key-value data per player (`ctx.player.data`) and globally (`mc.data`), auto-persisted to JSON.
- **Permission system** — Integrates with the Fabric Permissions API (supports both OP-based and permissions plugins like LuckPerms).
- **Player context** — Handlers receive a live `Player` wrapper object with readable properties (health, position, gamemode, etc.) and methods (`sendMessage`, `teleport`, `kick`, `give`).
- **Lua libraries** — Bundled `format.lua` (f-string-like templating) and `simple.lua` (concise command registration).

## Requirements

- Minecraft 1.21.x
- Fabric Loader ≥0.19.2
- Fabric API ≥0.141.4
- Fabric Language Kotlin ≥1.10.8

## Quick start

1. Install the mod on your Fabric server.
2. On first run, `config/pxrp/demo.lua` is created with example scripts.
3. Run `/pxrp reload` (requires operator level 4 or `pyxiion.pxrp` permission) to apply changes.

## Examples

### Basic command

```lua
register("fart", function(ctx)
    local player = ctx.player
    local pos = player.pos
    local dir = player.bodyDir

    broadcastFormat "*{p.name} farted*" {p = player}
    player.world:particle("minecraft:gust", pos.x - dir.x * 0.5, pos.y + 0.6, pos.z - dir.z * 0.5)
    mc.playSound("minecraft:entity.slime.squish", pos.x, pos.y, pos.z, player.world.name, 10, 0.1)
end)

```

### Arguments

Argument types can be given custom names with the `name:type` syntax:

```lua
register("rp kill", function(ctx, target)
    broadcastFormat "*{p.name} killed {t.name}*" {p = ctx.player, t = target}
end, "rp.kill")

-- Custom arg names are useful when you have multiple args of the same type:
register("try <action:text>", function(ctx, action)
    mc.broadcast(ctx.player.name .. " tries to " .. action)
end)

```

### Persistent player data

Every player has a `ctx.player.data` Lua table that persists automatically to disk.

```lua
register("coins", function(ctx)
    local bal = ctx.player.data.coins or 0
    mc.broadcast("You have " .. bal .. " coins")
end)

register("rp coins give", function(ctx)
    ctx.player.data.coins = (ctx.player.data.coins or 0) + 10
    mc.broadcast("+10 coins! Total: " .. ctx.player.data.coins)
end)

```

### Cross-player data

Data tables are shared — modifying another player's data from a command works:

```lua
register("rp pay <target:player>", function(ctx, target)
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
register("rp event", function()
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

Include these at the top of any script in `config/pxrp/` to use them.

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
| --- | --- | --- |
| `cmd` | `string` | Command path |
| `args` | `table` | Argument type list (same as `register`) |
| `template` | `string` | Format template (`{p.name}`, `{argName}`, etc.) |
| `range` | `number?` | If > 0, uses `broadcastInRange` with this radius |
| `overlay` | `boolean|number?` | Send as title overlay: `true` = 7s, or a custom tick count |

```lua
-- Global broadcast
registerSimple("wave", {}, "*{p.name} waves at everyone*", 15)    -- range 15 blocks
registerSimple("bow", {}, "*{p.name} bows*", nil, true)           -- title overlay
registerSimple("cheer", {}, "*{p.name} cheers*", 20, 60)          -- both

```

### Events

`mc.on(event, handler)` registers a handler that fires when a game event occurs. Handlers are cleared on `/pxrp reload`.

```lua
mc.on("player_join", function(player)
    mc.broadcast("Welcome, " .. player.name .. "!")
end)

mc.on("player_leave", function(player)
    mc.broadcast(player.name .. " left the server.")
end)

mc.on("player_death", function(player, damageType)
    mc.broadcast(player.name .. " died to " .. damageType)
end)

mc.on("player_chat", function(player, message)
    if message == "hello" then
        mc.broadcast(player.name .. " said hello!")
        return false -- cancel the player message
    end
end)

mc.on("server_start", function()
    mc.data.startTime = mc.time()
end)

mc.on("server_stop", function()
    local uptime = mc.time() - (mc.data.startTime or mc.time())
    mc.data.lastUptime = uptime
end)

```

| Event | Handler args | Fires | Cancellable |
| --- | --- | --- | --- |
| `player_join` | `player` | Player joins the server | ✅ |
| `player_leave` | `player` | Player disconnects | ❌ |
| `player_death` | `player`, `damageType` | Player dies (`"fall"`, `"player_attack"`, etc.) | ❌ |
| `player_chat` | `player`, `message` | Player sends a chat message | ✅ |
| `server_start` | — | Server finishes starting (after Lua reload) | ❌ |
| `server_stop` | — | Server is stopping (before save) | ❌ |

### Cancelling events

For `player_join` and `player_chat`, returning `false` from the handler cancels the action:

```lua
-- Kick the player if they're banned
mc.on("player_join", function(player)
    local bans = mc.data.bans or {}
    if bans[player.name] then
        return false  -- player is disconnected
    end
end)

-- Block messages containing swear words
mc.on("player_chat", function(player, message)
    local blocked = {"badword1", "badword2"}
    for _, word in ipairs(blocked) do
        if message:find(word) then
            return false  -- message is suppressed
        end
    end
end)

```

* `player_join`: fires before the player fully connects . Returning `false` disconnects them.
* `player_chat`: fires before the message is broadcast. Returning `false` blocks the message.
* Other events (`player_leave`, `player_death`, `server_start`, `server_stop`) are observational only — return values are ignored.
* Note: disconnecting a rejected player during `player_join` triggers `player_leave` as well. Scripts that broadcast on leave may show a ghost message for rejected players.

### Built-in Lua standard libraries

PxRP loads the following Lua standard libraries via [luaj](https://github.com/luaj/luaj) (targeting Lua 5.2):

| Library | Globals | Reference |
| --- | --- | --- |
| **Base** | `type`, `tostring`, `tonumber`, `pairs`, `ipairs`, `pcall`, `error`, `assert`, `select`, `unpack`, `_G`, etc. | [§2–6](https://www.google.com/search?q=https://www.lua.org/manual/5.1/manual.html%232) |
| **math** | `math.random`, `math.randomseed`, `math.floor`, `math.ceil`, `math.sin`, `math.cos`, `math.sqrt`, `math.min`, `math.max`, `math.pi`, `math.huge` | [§5.6](https://www.google.com/search?q=https://www.lua.org/manual/5.1/manual.html%235.6) |
| **string** | `string.format`, `string.sub`, `string.find`, `string.match`, `string.gmatch`, `string.gsub`, `string.len`, `string.byte`, `string.char`, `string.rep`, `string.lower`, `string.upper` | [§5.4](https://www.google.com/search?q=https://www.lua.org/manual/5.1/manual.html%235.4) |
| **table** | `table.insert`, `table.remove`, `table.sort`, `table.concat`, `table.maxn` | [§5.5](https://www.google.com/search?q=https://www.lua.org/manual/5.1/manual.html%235.5) |
| **bit32** | `bit32.band`, `bit32.bor`, `bit32.bxor`, `bit32.lshift`, `bit32.rshift`, `bit32.arshift`, `bit32.bnot` | [luaj docs](https://github.com/luaj/luaj#functions) |
| **package** | `require`, `package.path`, `package.loaded`, `package.preload` | [§5.3](https://www.google.com/search?q=https://www.lua.org/manual/5.1/manual.html%235.3) |

The following standard libraries are **not** loaded: `io`, `os`, `coroutine`, `debug`.

See the complete [Lua 5.2 Reference Manual](https://www.lua.org/manual/5.2/) for detailed documentation.

## Lua API

### `register(syntax, handler, permission?)`

Registers a Brigadier command. The syntax string combines the command path and argument definitions in a single string.

* `syntax` — Command syntax, e.g. `"msg <target:player> <msg:text>"`. Literal tokens form the path, `<name:type>` tokens define typed arguments. Missing `:type` on an angle-bracket token raises an error. Optional trailing args: `[<name:type>]` (everything from the first `[...]` onward is optional; missing options are `nil` in the handler). Subcommands use literal tokens: `"admin mute <target:player>"`.
* `handler` — Lua function called when the command executes. The first argument is always a Context object (`ctx`); subsequent arguments are the parsed command arguments in order.
* `permission` — Optional permission node string.

Supported argument types:

| Type | Lua value | Minecraft source |
| --- | --- | --- |
| `text` | string | Free-form message (trailing text, multi-word) |
| `word` | string | Single word (no quotes or spaces, `[a-zA-Z0-9._-]+`) |
| `player` / `target` | Player object | Player selector (must target exactly one player) |
| `int` | number | Integer value |
| `double` | number | Double-precision decimal |
| `float` | number | Single-precision decimal |
| `bool` | boolean | Boolean (`true`/`false`) |
| `block_pos` | table `{x, y, z}` | Block position (x y z coordinates) |
| `choice=a,b,c` | string | One of the comma-separated options (tab-completes, validates at runtime) |

Reserved commands — the following top-level command names cannot be overridden via `register()` (an error is thrown at registration time):
`pxrp`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist`. These are critical server management commands that must not be shadowed.

Optional arguments — everything from the first `[<name:type>]` onward is optional. Internally registers N+1 Brigadier variants so the handler is called without the optional arg(s). Missing params become `nil` in Lua.

Examples:

```lua
register("msg <target:player> <msg:text>",   handler)
register("mute <target:player>",             handler, "pxrp.mod")
register("setblocks <pos:block_pos> <block:text>", handler, "pxrp.admin")
register("homelist",                           handler)

-- Choice type — tab suggestions + validation
register("gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]", handler, "pxrp.admin")

-- Optional args — reason is nil when omitted
register("kick <target:player> [<reason:text>]", handler, "pxrp.mod")

```

### Context object

Every command handler receives a **Context** object as its first argument. The context exposes the executing player:

| Field | Type | Description |
| --- | --- | --- |
| `ctx.player` | `Player` | The player who executed the command |

### Player object

The Player object is accessed via `ctx.player` inside a command handler (or as the first arg in events like `player_join`). It's a live wrapper around the Minecraft player — every property read fetches the current state from the entity.

#### Properties

| Property | Type | Settable | Description |
| --- | --- | --- | --- |
| `name` | string | ❌ | Player name |
| `uuid` | string | ❌ | UUID string |
| `world` | World | ❌ | World wrapper (use `world.name` for the world path string) |
| `pos` | `{x, y, z}` | ❌ | Position |
| `dir` | `{x, y, z}` | ❌ | Look direction |
| `bodyDir` | `{x, z}` | ❌ | Body yaw direction |
| `health` | number | ✅ | Current health |
| `maxHealth` | number | ✅ | Max health (via attribute) |
| `food` | number | ✅ | Food level |
| `saturation` | number | ❌ | Saturation |
| `gamemode` | string | ✅ | Gamemode (`"survival"`, `"creative"`, etc.) |
| `ping` | number | ❌ | Latency (ms) |
| `xpLevel` | number | ❌ | Experience level |
| `xpProgress` | number | ❌ | XP progress (0–1) |
| `isOp` | boolean | ❌ | Operator status |
| `displayName` | string | ❌ | Display name |
| `isSneaking` | boolean | ❌ | Sneaking state |
| `isSprinting` | boolean | ❌ | Sprinting state |
| `selectedSlot` | number | ❌ | Hotbar slot |
| `fallDistance` | number | ❌ | Fall distance |
| `isFlying` | boolean | ❌ | Flying state |
| `air` | number | ✅ | Air ticks (max 300) |
| `maxAir` | number | ❌ | Max air ticks |
| `armor` | number | ✅ | Armor attribute |
| `armorToughness` | number | ✅ | Armor toughness attribute |
| `attackDamage` | number | ✅ | Base attack damage attribute |
| `attackSpeed` | number | ✅ | Attack speed attribute |
| `blockBreakSpeed` | number | ✅ | Block break speed attribute |
| `flyingSpeed` | number | ✅ | Flying speed attribute |
| `gravity` | number | ✅ | Gravity attribute |
| `knockbackResistance` | number | ✅ | Knockback resistance attribute |
| `luck` | number | ✅ | Luck attribute |
| `safeFallDistance` | number | ✅ | Safe fall distance attribute |
| `scale` | number | ✅ | Scale attribute |
| `speed` | number | ✅ | Movement speed attribute |
| `stepHeight` | number | ✅ | Step height attribute |
| `mainhand` | ItemStack | ✅ | Active hotbar slot item |
| `offhand` | ItemStack | ✅ | Offhand item |
| `head` | ItemStack | ✅ | Helmet slot item |
| `chest` | ItemStack | ✅ | Chestplate slot item |
| `legs` | ItemStack | ✅ | Leggings slot item |
| `feet` | ItemStack | ✅ | Boots slot item |
| `customName` | string | ✅ | Custom entity name |
| `tags` | table | ✅ | Scoreboard command tags proxy (`tags["foo"] = true`)

Setting a read-only property logs a warning and does nothing.

#### Methods

Methods are called with `:` syntax:

```lua
ctx.player:sendMessage("Hello!")
ctx.player:teleport(100, 64, 200)

```

| Method | Args | Description |
| --- | --- | --- |
| `sendMessage` | text | Sends a chat message |
| `sendActionBar` | text | Action bar overlay |
| `sendTitle` | title, [subtitle=nil] | Title + optional subtitle |
| `kick` | [reason="Kicked"] | Disconnects the player |
| `teleport` | x, y, z, [worldName=nil] | Teleport (intra-world or cross-dimension) |
| `damage` | amount | Deal generic damage |
| `heal` | amount | Heal health |
| `playSound` | id, [volume=1], [pitch=1] | Play a sound to the player |
| `give` | itemId/[item], [count=1] | Give item (string+count or ItemStack from `mc.createItem`) |
| `setItem` | slot, item | Set item in slot (ItemStack from `mc.createItem`) |
| `getItem` | slot | Get item from slot → ItemStack or nil |
| `clear` | — | Clear inventory |

#### Per-player persistent storage (`ctx.player.data`)

A Lua table that persists to disk (`<config>/pxrp/storage/players/<uuid>.json`). Data is written to disk when:

* The server stops
* A player disconnects
* `/pxrp reload` is executed

This batching approach improves performance for scripts that make multiple data assignments.

```lua
ctx.player.data.coins = (ctx.player.data.coins or 0) + 1          -- queued for write
ctx.player.data.inventory = {sword = 1, shield = 1}               -- queued for write

-- ❌ Attempting to index a missing sub-table directly will throw an error or fail to save:
ctx.player.data.nested.key = "value"                              

-- ✅ Fix (Ensure the sub-table exists, then re-assign to trigger write):
local t = ctx.player.data.nested or {}
t.key = "value"
ctx.player.data.nested = t                                         

```

### `mc.data` — Persistent global storage

A server-wide persistent table (`<config>/pxrp/storage/global.json`). Data is written to disk when:

* The server stops
* A player disconnects
* `/pxrp reload` is executed

```lua
mc.data.eventActive = true
mc.data.totalPlayers = (mc.data.totalPlayers or 0) + 1

```

### `mc.playSound(id, x, y, z, world, volume?, pitch?)`

Plays a sound at the given coordinates.

### `mc.broadcast(text, overlay?)`

Sends a chat message to all players. If `overlay` is a number, sends a title overlay for that many ticks.

### `mc.time()`

Returns the current server epoch time in seconds (as a double). Useful for cooldowns:

```lua
local last = ctx.player.data.lastFart or 0
if mc.time() - last < 10 then
    ctx.player:sendMessage("Wait " .. (10 - (mc.time() - last)) .. " seconds!")
    return
end
ctx.player.data.lastFart = mc.time()

```

### `mc.schedule(delay, callback)`

Runs a Lua function once after `delay` ticks (20 ticks = 1 second). Returns a task ID that can be used with `mc.cancelTask`.

```lua
mc.schedule(40, function()
    mc.broadcast("2 seconds have passed!")
end)

```

### `mc.scheduleRepeating(delay, interval, callback)`

Runs a Lua function repeatedly. The first execution happens after `delay` ticks, then every `interval` ticks after that. Returns a task ID.

```lua
local id = mc.scheduleRepeating(0, 20, function()
    local time = mc.data.gameTime or 0
    time = time + 1
    mc.data.gameTime = time
    mc.broadcast(time .. " second(s) have passed!")
end)

```

### `mc.cancelTask(id)`

Cancels a previously scheduled task by its ID. Returns `true` if the task was found and cancelled, `false` otherwise.

```lua
local id = mc.schedule(100, function()
    mc.broadcast("This will never run!")
end)
mc.cancelTask(id)

```

* All tasks are automatically cancelled on `/pxrp reload` and server stop.
* Callback errors are caught and logged per-task without affecting other tasks.
* **Important**: callbacks run on the server tick thread — do not perform blocking operations.

### `mc.players()` → table

Returns an array of [Player](#player-object) wrappers for all online players. Wrappers are cached per player UUID — repeated calls in hot loops reuse the same Lua objects without re-allocation.

```lua
for i, p in ipairs(mc.players()) do
    print(p.name, p.health)
end
```

### `mc.onlineCount` → number

The current number of online players. Lightweight — no table allocation, suitable for hot loops:

```lua
if mc.onlineCount == 0 then
    mc.broadcast("Server is empty!")
end
```

### `mc.world(name)` → World

Returns a World wrapper for the given dimension name (e.g. `"overworld"`, `"the_nether"`, `"the_end"`). The World object has properties and methods for world manipulation. You can also get the player's current world via `player.world`.

```lua
local w = mc.world("overworld")
w.time = w.time - (w.time % 24000) + 6000  -- set to noon
```

## World API

The World object is returned by `player.world` or `mc.world(name)`.

### Properties

| Property | Type | Settable | Description |
|----------|------|----------|-------------|
| `name` | string | ❌ | World path (e.g. `"overworld"`) |
| `time` | number | ✅ | Game time (ticks). Set to specific tick values |
| `raining` | boolean | ✅ | Whether rain/snow is falling |
| `thundering` | boolean | ✅ | Whether a thunderstorm is active |
| `players` | table | ❌ | Array of Player wrappers currently in this world |

```lua
local w = player.world
w.time = w.time - (w.time % 24000) + 1000   -- set to day
w.raining = true
w.thundering = false
```

### `world:spawn(entityId, pos, overrides?)` → Entity | nil

Creates and spawns an entity at the given position. `pos` accepts `{x, y, z}` or `{x=..., y=..., z=...}` tables. `entityId` auto-prefixes `minecraft:` if no namespace is present. Returns an Entity wrapper, or `nil` if spawning failed.

Optional `overrides` table:
| Field | Type | Description |
|-------|------|-------------|
| `custom_name` | string | Custom name tag |
| `health` | number | Health (for LivingEntity subclasses only). If the value exceeds the entity's default max health, `maxHealth` is automatically raised to match |

```lua
-- Spawn a zombie with 40 HP (normally max 20 — overrides bumps maxHealth)
local mob = w:spawn("zombie", {x = 100, y = 64, z = 200}, {
    health = 40,
})
if pig then
    pig.head = mc.createItem("minecraft:diamond_helmet", {name = "§bCrown"})
end
```

### Entity wrapper

The Entity wrapper is returned by `world:spawn()` and also backs `ctx.player` internally.

**Properties**: `uuid` (string), `type` (string), `name` (string), `displayName` (string), `customName` (string, rw), `pos` (`{x,y,z}`, rw), `dir` (`{x,y,z}`), `bodyDir` (`{x,z}`), `world` (World), `health` (number, rw), `maxHealth` (number, rw), `air` (number, rw), `maxAir` (number), `fallDistance` (number, rw), `fireTicks` (number, rw), `glowing` (boolean, rw), `invulnerable` (boolean, rw), `isSneaking` (boolean, rw), `isSprinting` (boolean, rw).

**Equipment properties** (rw, ItemStack or nil): `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`. For players, writes sync the inventory screen. For non-player entities, uses entity equipment API (tracker handles sync). Entities that don't support a slot (e.g. pig) return `nil` on read and silently ignore writes.

**Attribute properties** (rw, number): `speed`, `armor`, `armorToughness`, `attackDamage`, `attackSpeed`, `knockbackResistance`, `luck`, `stepHeight`, `blockBreakSpeed`, `gravity`, `scale`, `safeFallDistance`, `flyingSpeed`.

**Tags**: Command tags are exposed via a proxy table — `entity.tags["tagName"] = true` adds a tag, `entity.tags["tagName"] = false` removes it. Iterate via `pairs(entity.tags)`.

```lua
local pig = w:spawn("pig", {x = 100, y = 64, z = 200})
pig.tags["quest_mob"] = true
pig.speed = 0.5
pig.mainhand = mc.createItem("minecraft:stick", {name = "§eMagic Wand"})
```

### `world:setBlock(pos, blockId)`

Places a block at the given position. `blockId` defaults to `minecraft:` namespace if omitted. `pos` accepts `{x, y, z}` or `{x=..., y=..., z=...}` tables. Triggers full neighbor updates (redstone, water flow, observers).

```lua
player.world:setBlock({x = 0, y = 64, z = 0}, "diamond_block")
```

### `world:getBlock(pos)` → string

Returns the registry ID of the block at the given position, e.g. `"minecraft:stone"` or `"minecraft:air"`.

```lua
local block = player.world:getBlock({x = 0, y = 4, z = 0})
if block == "minecraft:air" then
    mc.broadcast("The floor was broken!")
end
```

### `world:fill(pos1, pos2, blockId)`

Fills a cuboid region. No neighbor updates are sent — blocks appear instantly to clients without causing observer/redstone cascades. Volume capped at 32,768 blocks (≈ 32×32×32). `pos1`/`pos2` accept position tables.

```lua
-- Reset an arena floor
player.world:fill({x = -10, y = 4, z = -10}, {x = 10, y = 4, z = 10}, "glass")

### `world:particle(particle, x, y, z)`

Spawns a particle at position visible to all players in that world.

```lua
player.world:particle("minecraft:gust", 0, 64, 0)
```

### `world:broadcastInRange(text, x, y, z, range, overlay?)`

Broadcasts text to players within range in that world.

```lua
player.world:broadcastInRange("Someone is nearby!", 0, 64, 0, 10)
```

### `mc.createItem(id, [count])` → ItemStack

Creates a simple stacked item. Returns an ItemStack object that can be used with `player:setItem` or `player:give`.

```lua
local arrows = mc.createItem("minecraft:arrow", 64)
player:setItem(9, arrows)
```

### `mc.createItem(id, components)` → ItemStack

Creates an item with custom components. `components` is a table with these optional fields:

| Field | Type | Description |
|---|---|---|
| `count` | int | Stack count (default 1) |
| `name` | string | Custom item name |
| `lore` | string[] | Lore lines |
| `custom_model_data` | int | Custom model data |
| `unbreakable` | bool | Makes item unbreakable |

```lua
local sword = mc.createItem("minecraft:diamond_sword", {
    name = "§cLegendary Sword",
    lore = {"Wielded by champions"},
    unbreakable = true,
    count = 1
})
player:setItem(0, sword)

-- Short form with just count
player:give(mc.createItem("minecraft:gold_ingot"))
```

### `mc.on(event, handler)`

Registers a Lua handler for a game event. See the [Events](#events) section for available events and examples.

## Storage

Data is stored as JSON in `config/pxrp/storage/`:

* `config/pxrp/storage/global.json` — Global data
* `config/pxrp/storage/players/<uuid>.json` — Per-player data

The storage backend is abstract (`DataBackend` interface). Currently ships with `JsonBackend`. The interface allows adding SQLite or PostgreSQL backends later without changing Lua code.

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](/search?q=LICENSE.txt).