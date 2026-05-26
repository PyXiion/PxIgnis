# PxRP

![version](https://img.shields.io/badge/version-0.4.0-blue)

A Lua-scriptable roleplay command framework for Minecraft Fabric servers. Define custom chat commands and complex server logic using Lua scripts — no Java or Kotlin mod code required.

> This project is developed with the assistance of AI. Humans were harmed (and included) during development too.

## Index

- [Features](#features)
- [Quick Start](#quick-start)
- [Examples](#examples)
- [Registering Commands](#registering-commands)
- [`mc.*` API](#mc-api)
- [Player API](#player-api)
- [World API](#world-api)
- [Entity Wrapper](#entity-wrapper)
- [Structure Wrapper](#structure-wrapper)
- [Bundled Lua Libraries](#bundled-lua-libraries)
- [Events Reference](#events-reference)
- [Built-in Lua Standard Libraries](#built-in-lua-standard-libraries)
- [Storage](#storage)
- [License](#license)

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks.
- **Event system** — React to 17 game events: player join/leave/death/chat/kill/damage/hurt, block break/place, item use, entity attack/interact, entity hurt/damage, and server lifecycle with Lua handlers (9 cancellable).
- **Dynamic reload** — `/pxrp reload` re-executes all Lua scripts instantly without restarting the server. All Lua state is torn down and rebuilt — persistent data must use `mc.data`/`player.data`.
- **Rich argument types** — Supports `text`, `word`, `target`/`player`, `int`, `double`, `float`, `bool`, `block_pos`, and custom choices (`choice=a,b,c`) with validation.
- **Minecraft API exposed to Lua** — Trigger particles, sounds, global/range broadcasting, block manipulation, entity spawning, world time/weather control, and server time access.
- **Persistent data storage** — Key-value data per player (`ctx.player.data`) and globally (`mc.data`), auto-persisted to JSON.
- **Permission system** — Integrates with the Fabric Permissions API (supports both OP-based and permissions plugins like LuckPerms).
- **Player context** — Handlers receive a live `Player` wrapper object with readable properties (health, position, gamemode, etc.) and methods (`sendMessage`, `teleport`, `kick`, `give`).
- **Structure loading** — Load and place Minecraft structure files with rotation, mirroring, and per-entity Lua callbacks.
- **Entity API** — `entity:damage(amount, source?)`, `entity:raycast(range)`, `entity:addEffect/removeEffect/hasEffect`, `entity:setOnFireFor(ticks)`, `entity:readNbt()/writeNbt(table)`.
- **Debug dumping** — `mc.dump(obj, depth?)` prints any Lua value as readable nested output with cycle detection.
- **Metatable extensions** — `mc.getMetatable("player"/"entity"/"world"/"structure")` allows adding custom methods to all wrappers of that type.
- **Per-player sidebar** — `player.sidebar = {title = "...", lines = {...}}` for packet-based scoreboard display.
- **Lua libraries** — Bundled `format.lua` (f-string-like templating) and `simple.lua` (concise command registration).

## Quick Start

- Minecraft 1.21.x, Fabric Loader ≥0.19.2, Fabric API ≥0.141.4, Fabric Language Kotlin ≥1.10.8

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

### Persistent data

Player data and global data persist to JSON automatically:

```lua
register("coins", function(ctx)
    local bal = ctx.player.data.coins or 0
    mc.broadcast("You have " .. bal .. " coins")
end)

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

### Events

```lua
mc.on("player_join", function(player)
    mc.broadcast("Welcome, " .. player.name .. "!")
end)

mc.on("player_chat", function(player, message)
    if message:find("badword") then
        player:sendMessage("NO BAD WORDS ON MY SERVER!")
        return false  -- suppress the message
    end
end)

mc.on("server_start", function()
    mc.data.startTime = mc.time()
end)
```

---

## Registering Commands

### `register(syntax, handler, permission?)`

```
register("cmd <name:type> [<name:type>]", handler, permission?)
```

| Part | Meaning |
|------|---------|
| `cmd` `sub` | Literal path tokens |
| `<name:type>` | Required argument. Missing `:type` raises parse error |
| `[<name:type>]` | Optional trailing argument. Everything from first `[...]` onward is optional. Missing → `nil` |
| `<name:choice=x,y>` | Choice type — runtime validation, tab completions |

**Types**: `text` (multi-word), `word` (single word), `player` (or `target` alias), `int`, `double`, `float`, `bool`, `block_pos` (returns `{x,y,z}`), `choice=opt1,opt2,...`

**Handler**: `function(ctx, arg1, arg2, ...)` — `ctx.player` is a live wrapper. `ctx.player.data` is per-player DataTable.

**Reserved commands** — the following top-level command names cannot be overridden via `register()`:
`pxrp`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist`.

**Optional arguments** — everything from the first `[<name:type>]` onward is optional. Missing params become `nil` in Lua.

Examples:

```lua
register("msg <target:player> <msg:text>",                                           handler)
register("mute <target:player>",                                                     handler, "pxrp.mod")
register("setblocks <pos:block_pos> <block:text>",                                   handler, "pxrp.admin")
register("homelist",                                                                 handler)
register("gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]", handler, "pxrp.admin")
register("kick <target:player> [<reason:text>]",                                     handler, "pxrp.mod")
```

### Context object

Every command handler receives a **Context** object as its first argument:

| Field | Type | Description |
|-------|------|-------------|
| `ctx.player` | `Player` | The player who executed the command |

---

## `mc.*` API

### `mc.broadcast(text, overlay?)`

Sends a chat message to all players. If `overlay` is a number, sends a title overlay for that many ticks.

### `mc.playSound(id, x, y, z, world, volume?, pitch?)`

Plays a sound at the given coordinates.

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

### `mc.dump(obj, depth?)`

Prints any Lua value to console as formatted nested output. Supports tables, functions, cycles (detected and shown as `{...}`), and custom `__pairs` metamethods. Returns the string.

```lua
mc.dump({a = 1, b = {c = "hello"}})
-- {
--   a = 1,
--   b = {
--     c = "hello",
--   },
-- }

-- Custom depth (default 3)
mc.dump(ctx.player, 1)
```

### `mc.schedule(delay, callback)`

Runs a Lua function once after `delay` ticks (20 ticks = 1 second). Returns a task ID.

```lua
mc.schedule(40, function()
    mc.broadcast("2 seconds have passed!")
end)
```

### `mc.scheduleRepeating(delay, interval, callback)`

Runs a Lua function repeatedly. First execution after `delay` ticks, then every `interval` ticks. Returns a task ID.

```lua
local id
id = mc.scheduleRepeating(0, 20, function()
    local time = mc.data.gameTime or 0
    time = time + 1
    mc.data.gameTime = time
    mc.broadcast(time .. " second(s) have passed!")
end)

-- THIS WON'T WORK (because it's Lua)

local id = mc.scheduleRepeating(0, 20, function()
    mc.cancelTask(id) -- id will be nil
end)

-- FIX
local id
id = mc.scheduleRepeating(0, 20, function()
    mc.cancelTask(id) -- Now works fine =D
end)
```

### `mc.cancelTask(id)`

Cancels a task by its ID. Returns `true` if found and cancelled, `false` otherwise.

```lua
local id = mc.schedule(100, function()
    mc.broadcast("This will never run!")
end)
mc.cancelTask(id)
```

* All tasks are automatically cancelled on `/pxrp reload` and server stop.
* Callback errors are caught and logged per-task without affecting other tasks.
* Callbacks run on the server tick thread — do not perform blocking operations.

### `mc.players()` → table

Returns an array of [Player](#player-api) wrappers for all online players. Wrappers are cached per UUID — repeated calls reuse the same Lua objects.

```lua
for i, p in ipairs(mc.players()) do
    print(p.name, p.health)
end
```

### `mc.onlineCount` → number

The current number of online players

```lua
if mc.onlineCount == 0 then
    mc.broadcast("Server is empty!")
end
```

### `mc.getEntity(uuid)` → Entity

Looks up an entity by UUID across all worlds. Returns an [EntityWrapper](#entity-wrapper) or `nil` if not found.

```lua
local e = mc.getEntity("a1b2c3d4-...")
if e ~= nil then
    e:damage(10)
end
```

### `mc.world(name)` → World

Returns a [World](#world-api) wrapper for the given dimension name (e.g. `"overworld"`, `"the_nether"`, `"the_end"`). Also accessible via `player.world`.

```lua
local w = mc.world("overworld")
w.time = w.time - (w.time % 24000) + 6000  -- set to noon
```

### `mc.loadStructure(id)` → Structure

Loads a structure from the Minecraft structure block manager by registry ID (e.g. `"minecraft:igloo/igloo_top"`). Returns a [Structure](#structure-wrapper) wrapper.

```lua
local s = mc.loadStructure("minecraft:igloo/igloo_top")
mc.broadcast("Size: " .. s.size.x .. "x" .. s.size.y .. "x" .. s.size.z)
```

### `mc.loadStructureFile(path)` → Structure

Loads a structure from an `.nbt` file on disk. Path is relative to the server root or absolute.

```lua
local s = mc.loadStructureFile("config/pxrp/mybuild.nbt")
s:place(player.world, {x = 0, y = 64, z = 0})
```

### `mc.createItem(id, [count | components])` → ItemStack

Creates an ItemStack. Short form: `mc.createItem(id, count)`. Extended form with a components table (returned ItemStacks expose `id`, `count`, and `custom_model_data` as read-only properties):

| Field | Type | Description |
|-------|------|-------------|
| `count` | int | Stack count (default 1) |
| `name` | string | Custom item name |
| `lore` | string[] | Lore lines |
| `custom_model_data` | int | Custom model data value |
| `unbreakable` | bool | Makes item unbreakable |
| `attackDamage` | number | Sets base attack damage (adds attribute modifier) |

```lua
local arrows = mc.createItem("minecraft:arrow", 64)
local sword = mc.createItem("minecraft:diamond_sword", {
    name = "§cLegendary Sword",
    lore = {"Wielded by champions"},
    unbreakable = true,
    count = 1
})
player:setItem(0, sword)
player:give(mc.createItem("minecraft:gold_ingot"))
```

### `mc.data` — Persistent global storage

A server-wide persistent table (`config/pxrp/storage/global.json`). Data is written on server stop, player disconnect, and `/pxrp reload`.

```lua
mc.data.eventActive = true
mc.data.totalPlayers = (mc.data.totalPlayers or 0) + 1
```

### `mc.getMetatable(name)` → table

Returns a singleton LuaTable for one of 4 wrapper types. Functions set on these tables become available on all wrappers of that type via `__index` fallthrough.

| Name | Affects |
|------|---------|
| `"player"` | Player wrappers (checked before entity) |
| `"entity"` | Entity wrappers (Player delegates here) |
| `"world"` | World wrappers |
| `"structure"` | Structure wrappers |

```lua
local meta = mc.getMetatable("entity")
meta.myFunc = function(self) return self.name end
-- Now `entity:myFunc()` works on ALL entities
```

Methods are colon-callable (receive `self` as arg1).

### `mc.on(event, handler)`

Registers a Lua handler for a game event. See [Events Reference](#events-reference) for available events.

---

## Player API

The Player object is accessed via `ctx.player` inside a command handler, or as the first argument in events like `player_join`. It's a live wrapper around the Minecraft player — every property read fetches current state from the entity.

### Properties

| Property | Type | Settable | Description |
|----------|------|----------|-------------|
| `name` | string | ❌ | Player name |
| `uuid` | string | ❌ | UUID string |
| `world` | World | ❌ | World wrapper (use `world.name` for the path string) |
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
| `customName` | string | ✅ | Custom name tag (nil clears) |
| `isSneaking` | boolean | ✅ | Sneaking state |
| `isSprinting` | boolean | ✅ | Sprinting state |
| `selectedSlot` | number | ❌ | Hotbar slot |
| `fallDistance` | number | ✅ | Fall distance |
| `isFlying` | boolean | ❌ | Flying state |
| `air` | number | ✅ | Air ticks (max 300) |
| `removed` | boolean | ❌ | Entity removed from world |
| `tags` | table | ✅ | Command tags proxy (`tags["foo"] = true`) |
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
| `tags` | table | ✅ | Scoreboard command tags proxy (`tags["foo"] = true`) |

Setting a read-only property logs a warning and does nothing.

### Methods

Methods are called with `:` syntax:

```lua
ctx.player:sendMessage("Hello!")
ctx.player:teleport(100, 64, 200)
```

| Method | Args | Description |
|--------|------|-------------|
| `sendMessage` | text | Sends a chat message |
| `sendActionBar` | text | Action bar overlay |
| `sendTitle` | title, [subtitle=nil] | Title + optional subtitle (fade 20/60/20 ticks) |
| `kick` | [reason="Kicked"] | Disconnects the player |
| `teleport` | x, y, z, [worldName=nil] | Teleport (intra-world or cross-dimension) |
| `damage` | amount | Deal generic damage |
| `heal` | amount | Heal health |
| `playSound` | id, [volume=1], [pitch=1] | Play a sound to the player |
| `give` | item | Give item — either a string (e.g. `"minecraft:diamond 5"`) or an ItemStack wrapper |
| `setItem` | slot, item | Set item in inventory slot (ItemStack or nil) |
| `getItem` | slot | Get item from slot → ItemStack or nil |
| `clear` | — | Clear entire inventory |

### Per-player sidebar (`player.sidebar`)

A packet-based per-player scoreboard sidebar that does not affect the global scoreboard.

```lua
-- Create sidebar
player.sidebar = {
    title = "My Server",
    lines = {"Line 1", "Line 2", "Line 3"}
}

-- Update parts
player.sidebar.title = "New Title"
player.sidebar.lines = {"Updated!"}

-- Remove
player.sidebar = nil
```

The sidebar persists across worlds and reconnects (restored 2 ticks after join).

### Per-player persistent storage (`ctx.player.data`)

A Lua table that persists to disk (`config/pxrp/storage/players/<uuid>.json`). Data is written on server stop, player disconnect, and `/pxrp reload`.

```lua
ctx.player.data.coins = (ctx.player.data.coins or 0) + 1
ctx.player.data.inventory = {sword = 1, shield = 1}

-- ❌ Nested sub-tables require re-assignment:
ctx.player.data.nested.key = "value"

-- ✅ Correct pattern:
local t = ctx.player.data.nested or {}
t.key = "value"
ctx.player.data.nested = t
```

---

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

### Methods (called with `:` syntax)

#### `world:spawn(entityId, pos, overrides?)` → Entity | nil

Creates and spawns an entity. `pos` accepts `{x, y, z}` or `{x=..., y=..., z=...}`. `entityId` auto-prefixes `minecraft:` if no namespace.

| Override | Type | Description |
|----------|------|-------------|
| `custom_name` | string | Custom name tag |
| `health` | number | Health (for LivingEntity). If exceeding default max, `maxHealth` is raised to match |

```lua
local mob = w:spawn("zombie", {x = 100, y = 64, z = 200}, {
    health = 40,
})
```

#### `world:setBlock(pos, blockId)`

Places a block. `blockId` defaults to `minecraft:` if omitted. Triggers full neighbor updates (redstone, water flow, observers).

```lua
player.world:setBlock({x = 0, y = 64, z = 0}, "diamond_block")
```

#### `world:getBlock(pos)` → string

Returns the registry ID of the block at the given position, e.g. `"minecraft:stone"`.

```lua
local block = player.world:getBlock({x = 0, y = 4, z = 0})
if block == "minecraft:air" then
    mc.broadcast("The floor was broken!")
end
```

#### `world:fill(pos1, pos2, blockId)`

Fills a cuboid region. No neighbor updates — blocks appear instantly without observer/redstone cascades. Volume capped at 32,768 blocks.

```lua
player.world:fill({x = -10, y = 4, z = -10}, {x = 10, y = 4, z = 10}, "glass")
```

#### `world:particle(particle, x, y, z)`

Spawns a particle at position visible to all players in that world.

```lua
player.world:particle("minecraft:gust", 0, 64, 0)
```

#### `world:broadcastInRange(text, x, y, z, range, overlay?)`

Broadcasts text to players within range in that world.

```lua
player.world:broadcastInRange("Someone is nearby!", 0, 64, 0, 10)
```

#### `world:getEntities(pos, radius, typeFilter?)` → table

Returns an array of [EntityWrapper](#entity-wrapper) for entities within a radius. Optionally filters by entity type ID.

```lua
local mobs = w:getEntities({x = 0, y = 64, z = 0}, 10, "minecraft:zombie")
for i, e in ipairs(mobs) do
    e:damage(10)
end
```

---

## Entity Wrapper

Returned by `world:spawn()` and also backs `ctx.player` internally (player-only keys + delegation).

### Properties

| Property | Type | Settable | Description |
|----------|------|:--------:|-------------|
| `uuid` | string | ❌ | UUID string |
| `type` | string | ❌ | Entity type ID (e.g. `"minecraft:zombie"`) |
| `name` | string | ❌ | Entity name |
| `displayName` | string | ❌ | Display name |
| `customName` | string | ✅ | Custom name tag |
| `world` | World | ❌ | Current world |
| `pos` | `{x, y, z}` | ✅ | Position |
| `dir` | `{x, y, z}` | ❌ | Look direction |
| `bodyDir` | `{x, z}` | ❌ | Body yaw direction |
| `health` | number | ✅ | Current health |
| `maxHealth` | number | ✅ | Max health (via attribute) |
| `air` | number | ✅ | Air ticks |
| `maxAir` | number | ❌ | Max air ticks |
| `fallDistance` | number | ✅ | Fall distance |
| `fireTicks` | number | ✅ | Fire ticks |
| `glowing` | boolean | ✅ | Glowing effect |
| `invulnerable` | boolean | ✅ | Invulnerability |
| `isSneaking` | boolean | ✅ | Sneaking state |
| `isSprinting` | boolean | ✅ | Sprinting state |
| `removed` | boolean | ❌ | Entity removed from world |

### Equipment properties

All read-write, return `nil` if the entity doesn't support that slot (e.g. pig → `mainhand` returns `nil`). For players, writes sync the inventory screen. For non-player entities, uses entity equipment API (tracker handles sync).

| Property | Type |
|----------|------|
| `mainhand` | ItemStack or nil |
| `offhand` | ItemStack or nil |
| `head` | ItemStack or nil |
| `chest` | ItemStack or nil |
| `legs` | ItemStack or nil |
| `feet` | ItemStack or nil |

### Attribute properties

All read-write, number values. Modifies the attribute instance's `baseValue`.

`speed`, `armor`, `armorToughness`, `attackDamage`, `attackSpeed`, `knockbackResistance`, `luck`, `stepHeight`, `blockBreakSpeed`, `gravity`, `scale`, `safeFallDistance`, `flyingSpeed`

### Tags

Command tags are exposed via a proxy table — `entity.tags["tagName"] = true` adds a tag, `entity.tags["tagName"] = false` removes it. Iterate via `pairs(entity.tags)`.

```lua
entity.tags["quest_mob"] = true
```

### Methods

#### `entity:damage(amount, sourceEntity?)`

Damages the entity. If `sourceEntity` (a table/EntityWrapper with a `uuid` field) is provided, enables knockback via player/mob attack source.

```lua
entity:damage(10)                      -- generic damage, no knockback
entity:damage(10, ctx.player)          -- player attack with knockback
```

#### `entity:raycast(range, includeFluids?)` → Entity | `{x, y, z}` | nil

Raycasts from the entity's eyes. Returns the closest LivingEntity hit, a position table `{x, y, z}` if a block is hit, or `nil`.

```lua
local target = entity:raycast(50)
if target and target.type == "minecraft:villager" then
    mc.broadcast("Looking at a villager!")
end
```

#### `entity:addEffect(effectId, duration, amplifier?, particles?, icon?)` → boolean

Adds a status effect. All params after `duration` are optional.

```lua
entity:addEffect("minecraft:speed", 100, 2)  -- speed III for 5 seconds
```

#### `entity:removeEffect(effectId)` → boolean

Removes a status effect by ID.

#### `entity:hasEffect(effectId)` → boolean

Checks if the entity has a specific effect.

#### `entity:setOnFireFor(ticks)`

Sets the entity on fire. Instantly syncs the fire visual to clients (unlike setting `fireTicks` directly).

```lua
entity:setOnFireFor(100)  -- 5 seconds of fire
```

### NBT methods

- `entity:readNbt()` → table — Returns a full NBT snapshot of the entity as a Lua table (recursive, handles all 12 NBT types).
- `entity:writeNbt(table)` — Applies a complete NBT snapshot from a Lua table. Full-snapshot replacement — partial tables **will reset** unmentioned fields to defaults. Read → modify → write pattern is safe.

```lua
-- Inspect entity NBT
local nbt = pig:readNbt()
mc.dump(nbt)

-- Clone entity data to another entity
local nbt = pig:readNbt()
nbt["CustomName"] = "Cloned Pig"
otherPig:writeNbt(nbt)
```

### Full example

```lua
local pig = w:spawn("pig", {x = 100, y = 64, z = 200})
pig.tags["quest_mob"] = true
pig.speed = 0.5
pig.mainhand = mc.createItem("minecraft:stick", {name = "§eMagic Wand"})
```

---

## Structure Wrapper

The Structure object is returned by `mc.loadStructure()` and `mc.loadStructureFile()`.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `size` | `{x, y, z}` | Structure dimensions in blocks |

### `structure:place(world, pos, params?)` → boolean

Places the structure at the given position.

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `rotation` | string | `"none"` | `"none"`/`"0"`, `"clockwise_90"`/`"90"`, `"clockwise_180"`/`"180"`, `"counterclockwise_90"`/`"270"` |
| `mirror` | string | `"none"` | `"none"`, `"left_right"`, `"front_back"` |
| `on_entity` | function | `nil` | Per-entity callback when placing entities |

```lua
local s = mc.loadStructure("minecraft:igloo/igloo_top")
s:place(player.world, {x = 0, y = 64, z = 0}, {
    rotation = "90",
    mirror = "left_right",
})
```

#### Entity callback (`on_entity`)

When `on_entity` is provided, structure entities are placed individually. The callback receives an [EntityWrapper](#entity-wrapper) for each entity. Return `false` to skip spawning that entity.

```lua
local s = mc.loadStructure("minecraft:igloo/igloo_top")
s:place(player.world, {x = 0, y = 64, z = 0}, {
    on_entity = function(e)
        if e.type == "minecraft:villager" then
            e.customName = "Custom Villager"
            return true
        end
        return false  -- skip all other entities
    end,
})
```

Positions are transformed (rotated/mirrored) to match the structure's placement. Entity UUIDs are regenerated automatically.

---

## Bundled Lua Libraries

Loaded via `require` at the top of any script in `config/pxrp/`:

```lua
require "format"    -- provides format() and broadcastFormat()
require "simple"    -- provides registerSimple()
```

### `format.lua` — template engine

Templates use `{expr}` placeholders with dot-notation access:

```lua
format(pattern)(args)              -- returns formatted string
broadcastFormat(pattern)(args)     -- formats and broadcasts in one call

broadcastFormat "*{p.name} throws a fireball at {t.name}*" {p = ctx.player, t = target}
```

### `simple.lua` — concise command registration

`registerSimple(cmd, args, template, range?, overlay?)` creates a command that formats and broadcasts a template, passing `p = ctx.player` automatically:

| Param | Type | Description |
|-------|------|-------------|
| `cmd` | `string` | Command path |
| `args` | `table` | Argument type list (same as `register`) |
| `template` | `string` | Format template (`{p.name}`, `{argName}`, etc.) |
| `range` | `number?` | If > 0, uses `broadcastInRange` with this radius |
| `overlay` | `boolean|number?` | Send as title overlay: `true` = 7s, or a custom tick count |

```lua
registerSimple("wave", {}, "*{p.name} waves at everyone*", 15)    -- range 15 blocks
registerSimple("bow", {}, "*{p.name} bows*", nil, true)           -- title overlay
registerSimple("cheer", {}, "*{p.name} cheers*", 20, 60)          -- both
```

---

## Events Reference

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

mc.on("player_block_break", function(player, pos, block)
    if block == "minecraft:bedrock" then
        return false  -- prevent breaking bedrock
    end
end)

mc.on("player_block_place", function(player, pos, block)
    if block == "minecraft:tnt" then
        return false  -- prevent placing TNT
    end
end)
```

| Event | Handler args | Fires | Cancellable |
|-------|--------------|-------|:-----------:|
| `server_start` | — | Server finishes starting (after Lua reload) | ❌ |
| `server_stop` | — | Server is stopping (before save) | ❌ |
| `player_join` | `player` | Player joins the server | ✅ |
| `player_leave` | `player` | Player disconnects | ❌ |
| `player_death` | `player`, `damageType` | Player dies (`"fall"`, `"player_attack"`, etc.) | ❌ |
| `player_chat` | `player`, `message` | Player sends a chat message | ✅ |
| `player_block_break` | `player`, `pos`, `block` | Player is about to break a block | ✅ |
| `player_block_place` | `player`, `pos`, `block` | Player is about to place a block | ✅ |
| `player_use_item` | `player`, `hand`, `itemStack`, `itemId` | Player right-clicks with item | ✅ |
| `player_attack_entity` | `player`, `entity` | Left-click on entity | ✅ |
| `player_interact_entity` | `player`, `entity` | Right-click on entity | ✅ |
| `player_hurt` | `player`, `damageType`, `amount` | Before player takes damage | ✅ |
| `entity_hurt` | `entity`, `damageType`, `amount` | Before entity (non-player) takes damage | ✅ |
| `player_damage` | `player`, `damageType`, `damageTaken`, `blocked` | After player takes damage | ❌ |
| `entity_damage` | `entity`, `damageType`, `damageTaken`, `blocked` | After entity (non-player) takes damage | ❌ |
| `player_kill` | `player`, `killedEntity`, `damageType` | Player kills another entity | ❌ |

**Notes**:
- `hand` is `"main"` or `"off"`
- `itemStack` is an [ItemStack](#mc-createitem-id-count-components---itemstack) wrapper or `nil`
- `damageType` is the last segment after `.` (e.g. `"fall"`, `"player_attack"`, `"generic"`)
- `blocked` is a boolean — `true` if a shield was used

### Cancelling events

For cancellable events (marked ✅ above), returning `false` cancels the action:

```lua
-- Kick banned players on join
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

* `player_join`: fires before the player fully connects. Returning `false` disconnects them.
* `player_chat`: fires before the message is broadcast. Returning `false` blocks the message.
* `player_block_break`: fires before the block is removed. Returning `false` cancels the break.
* `player_block_place`: fires before the block is placed. Returning `false` cancels the placement.
* `player_use_item`: fires before item use. Returning `false` prevents using the item.
* `player_attack_entity`: fires before attack. Returning `false` cancels the attack.
* `player_interact_entity`: fires before interaction. Returning `false` cancels the interaction.
* `player_hurt`/`entity_hurt`: fires before damage is applied. Returning `false` makes the entity immune to that damage.
* Other events (`player_leave`, `player_death`, `player_damage`, `entity_damage`, `player_kill`, `server_start`, `server_stop`) are observational only — return values are ignored.
* Disconnecting a rejected player during `player_join` triggers `player_leave` as well. Scripts that broadcast on leave may show a ghost message for rejected players.

---

## Built-in Lua Standard Libraries

PxRP loads the following Lua standard libraries via [luaj](https://github.com/luaj/luaj) (targeting Lua 5.2):

| Library | Globals | Reference |
|---------|---------|-----------|
| **Base** | `type`, `tostring`, `tonumber`, `pairs`, `ipairs`, `pcall`, `error`, `assert`, `select`, `unpack`, `_G`, etc. | [§2–6](https://www.lua.org/manual/5.1/manual.html#2) |
| **math** | `math.random`, `math.randomseed`, `math.floor`, `math.ceil`, `math.sin`, `math.cos`, `math.sqrt`, `math.min`, `math.max`, `math.pi`, `math.huge` | [§5.6](https://www.lua.org/manual/5.1/manual.html#5.6) |
| **string** | `string.format`, `string.sub`, `string.find`, `string.match`, `string.gmatch`, `string.gsub`, `string.len`, `string.byte`, `string.char`, `string.rep`, `string.lower`, `string.upper` | [§5.4](https://www.lua.org/manual/5.1/manual.html#5.4) |
| **table** | `table.insert`, `table.remove`, `table.sort`, `table.concat`, `table.maxn` | [§5.5](https://www.lua.org/manual/5.1/manual.html#5.5) |
| **bit32** | `bit32.band`, `bit32.bor`, `bit32.bxor`, `bit32.lshift`, `bit32.rshift`, `bit32.arshift`, `bit32.bnot` | [luaj docs](https://github.com/luaj/luaj#functions) |
| **package** | `require`, `package.path`, `package.loaded`, `package.preload` | [§5.3](https://www.lua.org/manual/5.1/manual.html#5.3) |

The following standard libraries are **not** loaded: `io`, `os`, `coroutine`, `debug`.

See the complete [Lua 5.2 Reference Manual](https://www.lua.org/manual/5.2/) for detailed documentation.

---

## Storage

Data is stored as JSON in `config/pxrp/storage/`:

* `config/pxrp/storage/global.json` — Global data (`mc.data`)
* `config/pxrp/storage/players/<uuid>.json` — Per-player data (`ctx.player.data`)

Data is persisted to disk on server stop, player disconnect, and `/pxrp reload`.

The storage backend is abstract (`DataBackend` interface). Currently ships with `JsonBackend` (atomic writes via temp file + atomic move). The interface allows adding other backends later without changing Lua code.

---

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](/search?q=LICENSE.txt).
