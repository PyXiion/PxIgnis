---
title: Changelog
description: Release history for PxIgnis.
---

# Changelog

## 0.14.1 — MetaTableBuilder inheritance rework, EntityFactory, wrapper cleanup (2026-06-22)

Just bug fixes & refactoring.

### Internal

- **MetaTableBuilder**: `inherit()` now takes a lazy `() -> LuaTable` instead of
  `BuiltMeta` so it updates dynamically (basically fixed metatables bug).
- **EntityFactory**: New centralized `EntityFactory.wrap(entity)` that detects
  that detects the type & uses the best wrapper
- **PlayerListWrapper**: Filters out removed players from the cached list (bugfix)
- **MetaTableBuilder test suite**: Full coverage for inheritance, method
  fallthrough, lazy caching, setters, pairs merging, and multi-level chains.
- `Iterator<LuaValue>.toLuaArray` helper (refactor).

## 0.14.0 — Entity pos simplification, new argument types, bugfixes (2026-06-21)

### Breaking changes

- **`entity.pos` is now a plain vector** — `entity.pos.x = 5` no longer works. Use `entity.pos = newPos`

### New API

| API                           | Description                           |
|-------------------------------|---------------------------------------|
| `vec:length()`                | Returns sqrt(x²+y²+z²)                |
| `<name:entity>` arg type      | Brigadier entity selector → wrapper   |

### Bugfixes

- **`vec(x, y, z)` validation inverted** — the `require(args.narg() == 3)` check was
  incorrectly `!= 3`, causing valid 3-arg calls to throw.
- **Raycast** — fixed crash

### Internal

- `Scheduler` — `LuaClosure` paths use `resumeOrLog` with full stack traces; plain
  `LuaFunction` callbacks avoid coroutine overhead.
- `AsyncLib` (`mc.sleep`, `mc.fetch`) — uses `resumeOrLog` helper to log coroutine
  errors instead of silently failing.
- `CommandRegistrar` — command execution errors now include full stack traces.
- `MetaTableBuilder.__index` — uses `mt.get(key)` instead of `mt.rawget(key)` so
  that `__index` on the metatable itself is properly followed.
- `EntityWrap.pos` — simplified from lazy-cached proxy metatable to direct
  `Vec3d` read/write (removed `livePosTable`).
- `OperationHelper.checkNumber` — fixed operators bug where all `table + table` turned into 0.
- PxLuaNova regression test for `__add` on tables.

---

## 0.12.0 & 0.13.0 — Sync compiler, sandboxed require, item builder (2026-06-21)

### New API

#### `nova.sync()` — JIT compile to JVM bytecode

`nova.sync(fn)` JIT-compiles a Lua function for better performance. The compiled
function is cached and reused. See [Nova API](/reference/nova-api).

```lua
local add = nova.sync(\{ a, b -> a + b })
```

#### Sandboxed `require` - `core:` namespace

`require` now only loads modules from `config/ignis/`. Built-in libraries use a
`core:` prefix:

| Module       | Old                     | New (`core:` prefix)      |
|--------------|-------------------------|---------------------------|
| Format       | `require "format"`      | `require "core:format"`   |
| Simple       | `require "simple"`      | `require "core:simple"`   |
| Chest GUI    | `require "chestgui"`    | `require "core:chestgui"` |

Prefixes may become available later for user scripts.

#### `mc.createItem` — table-based signatures

`mc.createItem` now accepts a component table in addition to the positional form:

```lua
mc.createItem("minecraft:diamond_sword", {
  name   = "§bBlade",
  lore   = { "A legendary sword" },
  count  = 1,
  unbreakable = true,
  custom_model_data = 42,
})

-- Single-table form:
mc.createItem { id = "minecraft:stone", count = 64 }
```

#### ItemStack JSON codec

New `mc.serialise` and `mc.deserialise` for ItemStacks:

```lua
local json = mc.serialise("item", item)    -- ItemStack → JSON string
local item = mc.deserialise("item", json)   -- JSON string → ItemStack
```

#### Lambda literal syntax (`\{ ... }`)

PxLuaNova's lambda literal syntax is available in PxIgnis scripts. Opt in per file
with `--# nova syntax` on line&nbsp;1. See [Language extensions](/reference/language)
for all four forms: named-arg (`->`), zero-arg expression, zero-arg chunk, and
trailing-block sugar.

### Breaking changes

| Change | Migration |
|---|---|
| `require "format"` / `"simple"` / `"chestgui"` now require `core:` prefix | `require "core:format"` |
| Built-in Lua libs `io`, `os`, `debug` not loaded | Rely on `math`, `string`, `table`, `bit32`, `coroutine` |
| `collectgarbage`, `loadfile`, `dofile` removed from globals | Use `nova.sync()` or `require` for loading code |
| `world:playSound(id, x, y, z, volume?, pitch?)` → `world:playSound(id, pos, volume?, pitch?)` | Pass `vec(x, y, z)` as second argument |

### Internal

- `LuaCmdLoader.kt` split into `IgnisRuntime`, `ScriptEnvironment`, `ScriptLoader`
- New `MetaTableBuilder` DSL for cleaner metatable construction (used by all wrappers)
- `Vfs` + `LuaRequire` — sandboxed module loading replacing `PackageLib`
- Package reorganisation: `commands/`, `api/wrapper/`, `api/manager/`, `api/util/` subpackages
- `ItemBuilder`, `ItemStackCodec` — new utility classes
- Test suite cleanup: removed `/ignis reload`-dependent tests (`HologramManagerTest`, `RegionTest`)
- Built-in Lua libraries (`demo.lua`, `format.lua`, `simple.lua`, `chestgui.lua`) updated
- All reference docs updated; new guide page *Events & Storage*

---

## 0.11.0 — Region API (2026-06-15)

### New API — [Region](/reference/region-api)

Spatial areas in a world with event subscriptions. Create with two corners (auto-normalized), subscribe to
enter/leave/move/death/tick events with optional per-callback throttling.

| API                              | Description                                                          |
|----------------------------------|----------------------------------------------------------------------|
| `world:createRegion(posA, posB)` | Creates a region between two corners                                 |
| `r:getBounds()`                  | Returns `{A, B}` — the two bounding corners (A = min, B = max)       |
| `r:setBounds(posA, posB)`        | Updates region bounds; re-evaluates entity membership                |
| `r:on(event, fn, opts?)`         | Subscribes to a region event; returns handler ID                     |
| `r:off(id)`                      | Unsubscribes a handler by ID; returns `true` if removed              |
| `r:contains(pos)`                | `true` if position is inside the bounds                              |
| `r:destroy()`                    | Destroys region, fires `destroy` event, clears handlers              |
| `r.players`                      | Sequence of players currently inside                                 |
| `r.entities`                     | Sequence of entities currently inside                                |
| `r.id`                           | Session-unique integer ID                                            |
| `r.world`                        | World the region belongs to                                          |
| `world.regions`                  | Sequence of all live regions in this world                           |
| `mc.getRegion(id)`               | Looks up a region by ID across all worlds; returns `Region` or `nil` |
| `world:getRegion(id)`            | Looks up a region by ID in this world; returns `Region` or `nil`     |
| `world:getRegionsAt(pos)`        | Returns a sequence of regions in this world containing `pos`         |

**Events**: `entity_enter`, `entity_leave`, `entity_move`, `player_enter`, `player_leave`, `player_move`,
`entity_death`, `player_death`, `tick`, `destroy`.

```lua
local r = world:createRegion({x=0,y=0,z=0}, {x=100,y=64,z=100})
r:on("entity_enter", function(e) e:sendMessage("Welcome!") end)
r:on("entity_move", function(e, from, to) print((to - from):length()) end, { throttle = 5 })
r:on("tick", function() end) -- auto-subscribes, no enableTick needed

-- Look up a region later by ID (e.g. one stored in mc.data)
local saved = mc.getRegion(r.id)
-- Find all regions at a position
for _, reg in ipairs(world:getRegionsAt({ x = 50, y = 64, z = 50 })) do
  print("In region", reg.id)
end
```

### [Updated Event System](/reference/events)

The event system was refactored into a single `EventBus` class backing both `mc.on` and `region:on`. Changes:

- **`mc.on` now returns a handler ID** (integer). Pass it to `mc.off(id)` to unsubscribe.
- **`mc.off(id)`** — new global API. Returns `true` if the handler was removed.
- **`region:on` now returns a handler ID**. Pass it to `region:off(id)` to unsubscribe.
- **`mc.on("tick", fn)`** — now works globally, same auto-subscribe semantics as regions. Fires every server tick.
- **`mc.emit` / `mc.on`** signatures unchanged.

```lua
local id = mc.on("player_join", function(p) print(p.name) end)
mc.off(id)  -- remove handler

local rid = r:on("entity_enter", function(e) e:sendMessage("Hi!") end)
r:off(rid)
```

### Breaking changes

- **`mc.players()` → `mc.players`** — now a strict read-only list property, not a function. Callers must use
  `mc.players` without parentheses.

### Other

- **`world.players` refactored**. It's now cached per-tick.
- **`mc.getWorld(name)` now also caches**.

## 0.10.0 — Hologram API

### New API

| Function                                | Description                                                        |
|-----------------------------------------|--------------------------------------------------------------------|
| `world:spawnHologram(pos, text, opts?)` | Spawns a `minecraft:text_display` entity; returns hologram wrapper |
| `mc.holograms`                          | List of all live holograms (property)                              |
| `mc.getHologram(uuid)`                  | Returns hologram by UUID, or `nil`                                 |

Hologram wrapper exposes r/w properties: `text`, `lines`, `alignment`, `billboard`,
`lineWidth`, `background`, `opacity`, `shadow`, `seeThrough`, `glowing`, `pos`,
plus `:setLine(n, text)` and `:destroy()` methods. Delegates to entity metatable
for `uuid`, `world`, `removed`, `pos` live proxy, `tags`, NBT read/write, etc.

```lua
local h = world:spawnHologram({x=0,y=64,z=0}, "Hello\nWorld", {billboard="center"})
h.lines = {"Line 1", "Line 2"}
h:setLine(1, "Updated")
mc.schedule(40, function() h:destroy() end)
```

### Notes

- All holograms are destroyed on `/ignis reload`.
- Per-player holograms deferred as future work (would need custom packet dispatch).

## 0.9.0 — Removed ByteBuddy, 6 new events (23 total)

### New events

| Event              | Cancellable | Args                         | Fires                                                            |
|--------------------|-------------|------------------------------|------------------------------------------------------------------|
| `entity_spawn`     | ❌           | `entity`                     | Any entity loads into a world                                    |
| `entity_despawn`   | ❌           | `entity`                     | Any entity unloads from a world                                  |
| `entity_death`     | ✅           | `entity`, `source`, `amount` | Any living entity is about to die (players + mobs)               |
| `player_join_init` | ✅           | `player`                     | Early join, player not fully loaded (renamed from `player_join`) |
| `player_join`      | ❌           | `player`                     | Post-load join, player fully ready in world                      |
| `player_respawn`   | ❌           | `player`, `wasDeath`         | Player respawns after death or end portal                        |

**Migration**: `player_join` was renamed to `player_join_init` (still cancellable, early). The new `player_join`
fires later when the player is fully in-world.

### Removed: `mc.observeHook` / `mc.removeHook` / `mc.clearHooks`

ByteBuddy (`byte-buddy` + `byte-buddy-agent`) was pulling ~4.3 MB into the output jar for a single
unstable API (`mc.observeHook`). Use `mc.on()` events instead.

### Other changes

- Deleted `LuaMixinManager.kt` (ByteBuddy — 152 lines)
- Output jar reduced from ~4.9 MB to ~839 KB

## 0.8.0 — Async API, PxLuaNova embedded, documentation site

### Async API

- Added `mc.fetch(url)` — coroutine-yielding HTTP requests via `HttpClient.sendAsync`
- Added `mc.sleep(ticks)` — coroutine-yielding timed delay via `Scheduler.schedule`

### PxLuaNova composite build

- Embedded PxLuaNova as a Gradle composite build (`pxluanova/`) — no more Maven Local dependency
- Dependency auto-substitution via `includeBuild 'pxluanova'` in `settings.gradle`

### Documentation site

- New Astro + Starlight documentation site at `site/` (brand: PxIgnis)
- 28 documentation pages covering all APIs

### Other

- `.gitignore` patterns fixed to match nested directories (trailing-slash form)
- General cleanup and minor improvements

## 0.7.0 — Mob AI, ByteBuddy hooks, sidebar rewrite, PxLuaNova migration

### Migration: LuaJ → PxLuaNova

- Using a custom fork of LuaJ with fixes & performance improvements.

### Breaking changes

- **Sidebar API enhanced** — `player.sidebar` property now returns a sidebar object with `title`/`lines` (r/w),
  `show()`/`hide()`/`destroy()`/`setLine(n, text)`, and `visible`/`lineCount` properties.
  Assignment with `{title, lines, visible}` creates or updates the sidebar.
  Sidebar no longer auto-restores on reconnect — script must re-create it.

### New Mob AI system

| API                                 | Description                                                                             |
|-------------------------------------|-----------------------------------------------------------------------------------------|
| `mc.registerBehaviour(id, fn)`      | Registers a named AI behaviour function                                                 |
| `mob:setAI("id")` / `mob:setAI(fn)` | Assigns behaviour by name or directly with a function. Persists on reload if ID is used |
| `mob:clearAI()`                     | Removes behaviour, restores vanilla AI                                                  |
| `mob.aiActive`                      | Whether a behaviour is currently active                                                 |

**Mob properties** (delegates to entity metatable for entity-level props):

| Property            | Type          | Notes                        |
|---------------------|---------------|------------------------------|
| `mob.isMob`         | bool          | Always `true`                |
| `mob.target`        | entity or nil | Attack target (r/w by UUID)  |
| `mob.speed`         | number        | Movement speed attribute     |
| `mob.pathRemaining` | 0–1           | Navigation progress fraction |
| `mob.pathFound`     | bool          | Whether a path is active     |

**Mob methods**:

| Method                                               | Signature                       |
|------------------------------------------------------|---------------------------------|
|                                                      |                                 |
| `mob:navigateTo(x, y, z)` / `mob:navigateTo(entity)` | Pathfind to position or entity  |
| `mob:stopNavigation()`                               | Cancel current path             |
| `mob:lookAt(x, y, z)` / `mob:lookAt(entity)`         | Turn toward position or entity  |
| `mob:moveToward(vec, speed)`                         | Direct movement toward position |
| `mob:jump()`                                         | Trigger jump                    |
| `mob:canSee(entity)`                                 | Line-of-sight check             |
| `mob:distanceTo(entity)` / `mob:distanceTo(vec)`     | Euclidean distance              |

`world:spawn()` and `mc.getEntity()` automatically return `MobWrapper` for `MobEntity` subtypes.

Built-in behaviours: `guard`, `pet`, `orbiter`, `statue`, `wander` (check `demo_ai.lua`).

### ByteBuddy runtime method hooks (experimental, unstable)

| Function                                  | Description                                  |
|-------------------------------------------|----------------------------------------------|
| `mc.observeHook(class, method, callback)` | Hooks a Java method at runtime via ByteBuddy |
| `mc.removeHook(class, method)` → bool     | Removes a hook                               |
| `mc.clearHooks()`                         | Removes all hooks                            |

Hooks are cleared on `/ignis reload` and server stop. Callback receives `(instance, args)`. Only single-overload methods
supported. May be removed in future versions.

### New Lua API

| Function                     | Description                                                    |
|------------------------------|----------------------------------------------------------------|
| `mc.runtimeNamespace`        | Current mapping namespace (e.g. `"named"` or `"intermediary"`) |
| `mc.mapped(className)`       | Map named class → runtime class name                           |
| `mc.getMetatable("sidebar")` | Sidebar shared metatable                                       |
| `mc.getMetatable("mob")`     | Mob shared metatable                                           |

`mc.runtimeNamespace` & `mc.mapped(className)` have no purpose btw, hooks map names automatically.

### Sidebar API

```lua
player.sidebar = { title = "Title", lines = {"Line 1", "Line 2"} }
local sb = player.sidebar
sb.title = "New Title"             -- update title
sb.lines = {"Line 1", "Line 2"}   -- replace all lines
sb:setLine(1, "Updated")           -- update specific line
sb:show() / sb:hide() / sb:destroy()
sb.visible / sb.lineCount          -- read-only properties
```

### Async API (mc.fetch / mc.sleep)

Coroutines are now supported!

| Function                                                     | Description                                                                                              |
|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `mc.fetch(url)`                                              | Simple HTTP GET, yields coroutine                                                                        |
| `mc.fetch({url, method?, headers?, body?, json?, timeout?})` | Full request table. `json` auto-encodes Lua table as JSON body. `body` and `json` are mutually exclusive |
| `mc.sleep(ticks)`                                            | Yields the coroutine for `ticks` ticks (20 = 1s), resumes on server thread                               |

`mc.fetch` returns a response table with a shared metatable:

| Field         | Description                                    |
|---------------|------------------------------------------------|
| `res.ok`      | `true` for 2xx                                 |
| `res.status`  | HTTP status code                               |
| `res.text`    | Raw body string                                |
| `res.headers` | Response headers table                         |
| `res.json`    | Parsed JSON. Throws `LuaError` on invalid JSON |
| `res.error`   | Set only on network failures                   |

---

## 0.6.0 — Vector arithmetic, Inventory/Container API, Raycast, shared metatables

### Breaking changes

- `Player.kt` → `PlayerWrapper.kt`, `World.kt` → `WorldWrapper.kt` (internal refactor, no Lua API change)
- `player.world:particle(id, x, y, z)` → `player.world:particle(id, vec(x, y, z), opts?)` (positional args replaced by
  vector + options table)
- Removed dead `coerce/KotlinToLua.kt`

### New vector API

- `vec(x, y, z)` global constructor with `+`, `-`, `*`, `/`, `unm`, `==`, `tostring` operators
- Component-wise for `v1 * v2`, scalar for `v / n`, both `v * n` and `n * v`
- Vector metatable accessible via `mc.getMetatable("vec")`

### New inventory / container API

| API                                            | Description                                             |
|------------------------------------------------|---------------------------------------------------------|
| `mc.createInventory(size)`                     | Creates a virtual SimpleInventory (9–54, multiple of 9) |
| `inv:getItem(slot)`, `inv:setItem(slot, item)` | Slot access (1-based)                                   |
| `inv:fill(item)`, `inv:clear()`                | Bulk ops                                                |
| `inv:open(player, title?)`                     | Opens chest screen → Container                          |
| `container:onClick(callback)`                  | Registers click handler (auto-locks inventory)          |
| `container:onClick(nil)`                       | Unlocks inventory                                       |
| `container:close()`                            | Closes the screen                                       |

### New raycast API

Both `entity:raycast(range)` and `world:raycast(start, dir, range)` now return a result table:

```lua
-- Block hit:
{ type = "block", blockPos = Vec(...), hit = Vec(...), side = "north", normal = Vec(...) }

-- Entity hit:
{ type = "entity", entity = EntityWrapper, hit = Vec(...) }
```

### New world methods

- `world:raycast(startVec, dirVec, range, includeFluids?, includeEntities?)`
- `world:playSound(id, x, y, z, volume?, pitch?)`
- `world:particle(id, pos, opts?)` — vector position, options table with `count`, `spread`, `speed`, `data`

### New `ItemStack` features

- Item wrappers now use shared metatable (`mc.getMetatable("item")`)
- `mc.createItem` full component table (name, lore, unbreakable, attackDamage, etc.)
- `ItemStackWrapper.toJson`/`fromJson` for serialization

### Other additions

- `nbtToLua` / `luaToNbt` utility functions in `Utils.kt`
- `chestgui.lua` — chest GUI library with grid positioning
- All wrappers now use **shared metatables** (one per type) — `__index`, `__newindex`, `__pairs` on the metatable,
  methods via `rawset`
- `player:damage(amount)`, `player:heal(amount)`, `player:give(id/count or ItemStack)`, `player:setItem(slot, item)`,
  `player:getItem(slot)`, `player:clear()`

---

## 0.5.1 — Rebuild

No API changes. CI fix for Modrinth publish.

---

## 0.5.0 — Entity/Structure/Sidebar/Metatable APIs, 10 new events

### New `mc.*` functions

| Function                     | Signature                                                     |
|------------------------------|---------------------------------------------------------------|
| `mc.getMetatable(name)`      | `"entity"\|"player"\|"world"\|"structure"` → shared metatable |
| `mc.loadStructure(id)`       | string → structure table                                      |
| `mc.loadStructureFile(path)` | file path → structure table                                   |
| `mc.getEntity(uuid)`         | string → entity table or nil                                  |
| `mc.dump(value, maxDepth?)`  | recursive table debug dump                                    |
| `mc.emit(event, ...)`        | programmatic event firing                                     |
| `mc.players`                 | property — list of online player tables (cached)              |
| `mc.onlineCount`             | property — number of online players                           |

### New entity methods

| Method                                                          | Signature                    | Notes                                             |
|-----------------------------------------------------------------|------------------------------|---------------------------------------------------|
| `entity:readNbt()`                                              | → table                      | Dump entity NBT to Lua table                      |
| `entity:writeNbt(t)`                                            | ← table                      | Write NBT back to entity                          |
| `entity:raycast(range, includeFluids?)`                         | → entity or `{x,y,z}` or nil | Hits entities before blocks                       |
| `entity:damage(amount, sourceEntity?)`                          | —                            | With optional damage source                       |
| `entity:addEffect(id, duration, amplifier?, particles?, icon?)` | → bool                       | Status effect                                     |
| `entity:removeEffect(id)`                                       | → bool                       |                                                   |
| `entity:hasEffect(id)`                                          | → bool                       |                                                   |
| `entity:setOnFireFor(ticks)`                                    | —                            |                                                   |
| `entity.removed`                                                | r/o bool                     | Whether entity is removed                         |
| `entity.pos.x/y/z`                                              | r/w now live                 | Previously snapshot; now reads/writes current pos |

### New player methods

| Method                                                         | Signature                                                            |
|----------------------------------------------------------------|----------------------------------------------------------------------|
| `player:sendActionBar(text)`                                   |                                                                      |
| `player:sendTitle(title, subtitle?, fadeIn?, stay?, fadeOut?)` |                                                                      |
| `player:playSound(id, volume?, pitch?)`                        |                                                                      |
| `player:getItem(slot)` → item table or nil                     |                                                                      |
| `player:setItem(slot, item)`                                   | item from `mc.createItem` or nil                                     |
| `player:clear()`                                               | Clear inventory                                                      |
| `player.sidebar`                                               | r/w property — set to `{title="...", lines={...}}` or `nil` to clear |
| `player.sidebar.title`                                         | r/w string                                                           |
| `player.sidebar[i]`                                            | r/w lines (1-indexed)                                                |

### New world methods

| Method                                               | Signature                            | Notes                                 |
|------------------------------------------------------|--------------------------------------|---------------------------------------|
| `world:particle(id, pos, opts)`                      | **Moved from `mc.particle`**         | Particle visible to all in that world |
| `world:broadcastInRange(text, pos, range, overlay?)` | **Moved from `mc.broadcastInRange`** |                                       |
| `world:getEntities(pos, radius, typeFilter?)`        | → table of entity tables             | Spatial entity query                  |

### Removed from `mc.*`

- `mc.particle(...)` → use `world:particle(...)`
- `mc.broadcastInRange(...)` → use `world:broadcastInRange(...)`

### New events (10 added, 16 total)

Cancellable:

- `player_block_break(player, pos, blockId)`
- `player_block_place(player, pos, blockId)`
- `player_use_item(player, hand, item, itemId)`
- `player_attack_entity(player, entity)`
- `player_interact_entity(player, entity)`
- `player_hurt(player, source, amount)`
- `entity_hurt(entity, source, amount)`

Non-cancellable:

- `player_damage(player, source, damageTaken, blocked)`
- `entity_damage(entity, source, damageTaken, blocked)`
- `player_kill(player, killedEntity, source)`

### New types/values

- **Structure table** — `.size` (vector), `:place(world, pos, opts?)` (rotation, mirror, on_entity)
- **Shared metatables** — `mc.getMetatable("entity"|"player"|"world"|"structure")`
- **`item.custom_model_data`** — now readable on item tables
- **`mc.createItem(id, {attackDamage = N})`** — new option to set attack damage

### Other

- `world:spawn(id, pos, overrides?)` now returns entity with all new methods (raycast, damage, effects, NBT)
- `entity.dir`, `entity.bodyDir` now return `{x,y,z}` vector tables (previously Java userdata)
- Player cache: `mc.players` and `world.players` reuse wrappers across lookups
- Sidebar persists across world changes and reconnects (restored 2 ticks after join)
- `player.block_break`/`player.block_place` migrated from mixins to Fabric API events

---

## 0.4.0 — World/Entity API, tags, item creation

### New `mc.*` functions

| Function                                 | Signature                                   |
|------------------------------------------|---------------------------------------------|
| `mc.world(name)`                         | string → world table                        |
| `mc.createItem(id, countOrTable?)`       | → item table                                |
| `mc.setBlock(x,y,z,block,world)`         | **Removed in 0.5.0** — use `world:setBlock` |
| `mc.getBlock(x,y,z,world)`               | **Removed in 0.5.0** — use `world:getBlock` |
| `mc.fill(x1,y1,x2,y2,z1,z2,block,world)` | **Removed in 0.5.0** — use `world:fill`     |

### New world methods

| Method                                                          | Notes             |
|-----------------------------------------------------------------|-------------------|
| `world:spawn(id, pos, overrides?)`                              | → entity table    |
| `world:setBlock(pos, blockId)`                                  |                   |
| `world:getBlock(pos)`                                           | → block id string |
| `world:fill(pos1, pos2, blockId)`                               |                   |
| `world.name`, `world.time`, `world.raining`, `world.thundering` | r/w properties    |

### New entity properties

Entity table returned by `world:spawn()` and `player` delegation:

| Property                                                                                                                                                                                    | Type              | Notes                                                   |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|---------------------------------------------------------|
| `uuid`                                                                                                                                                                                      | string            |                                                         |
| `type`                                                                                                                                                                                      | string            | e.g. `"minecraft:zombie"`                               |
| `name`, `displayName`, `customName`                                                                                                                                                         | string            |                                                         |
| `world`                                                                                                                                                                                     | table             | World wrapper (was string in 0.3.0)                     |
| `pos`                                                                                                                                                                                       | `{x,y,z}` vector  | r/w (snapshot in 0.4.0; live proxy in 0.5.0)            |
| `dir`, `bodyDir`                                                                                                                                                                            | `{x,y,z}` vector  |                                                         |
| `health`, `maxHealth`                                                                                                                                                                       | number            |                                                         |
| `fallDistance`, `fireTicks`                                                                                                                                                                 | number            |                                                         |
| `glowing`, `invulnerable`                                                                                                                                                                   | boolean           |                                                         |
| `isSneaking`, `isSprinting`                                                                                                                                                                 | boolean           |                                                         |
| `air`, `maxAir`                                                                                                                                                                             | number            |                                                         |
| `tags`                                                                                                                                                                                      | boolean proxy     | `pairs(entity.tags)`, `entity.tags["tag"] = true/false` |
| `speed`, `armor`, `attackDamage`, `maxHealth_attr`, `followRange`, `knockbackResistance`, `luck`, `horseJump`, `flyingSpeed`, `armorToughness`, `movementEfficiency`, `scale`, `stepHeight` | number            | Attribute accessors (r/w where vanilla permits)         |
| `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`                                                                                                                                      | item table or nil | Equipment slots (writable)                              |

### Player changes

- `player.world` now returns a world table (was a world name string)
- `player:give(id, count)` also accepts item table from `mc.createItem`
- All entity properties delegated to entity metatable (player inherits pos, health, tags, equipment, etc.)

### New command argument types

- `word` — single-word string (`StringArgumentType.word()`)

### Other

- Block IDs auto-prefixed with `minecraft:` if no namespace given
- `demo.lua` no longer requires `return {...}` at file end
- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj` and Permissions API

---

## 0.3.0 — Scheduler, teleport fix, Lua arg fallback

### New `mc.*` functions

| Function                                                    | Signature                    |
|-------------------------------------------------------------|------------------------------|
| `mc.schedule(delayTicks, callback)`                         | → task id                    |
| `mc.scheduleRepeating(delayTicks, intervalTicks, callback)` | → task id                    |
| `mc.cancelTask(id)`                                         | → bool                       |
| `mc.data`, `player.data`                                    | Persistent key-value storage |

### Events

- `server_start`, `server_stop`
- `player_join(player)` — cancellable
- `player_leave(player)`, `player_death(player, source, message)`
- `player_chat(player, message)` — cancellable

### Player (initial API)

- `player:send(msg)`, `player:teleport(x,y,z)`, `player:kick(reason?)`
- `player:give(id, count)`, `player:damage(amount)`
- `player:getInventory()` → table of item stacks
- `player:getBlockPos()` → `{x,y,z}`
- `player.name`, `player.uuid`, `player.world`, `player.pos` (read-only snapshot)
- `player.health` (r/w), `player.displayName` (r/w)

### Commands

- `register(syntax, handler, permission?)` — literal/arg syntax with `<name:type>` and `[optional]`
- Types: `text`, `player` (alias `target`), `int`, `double`, `float`, `bool`, `block_pos`, `choice=a,b,c`
- Reserved commands: `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`,
  `save-off`, `whitelist`, `pxrp`

### Storage

- JSON storage: `mc.data` → global, `player.data` → per-player
- Saved on server stop, player disconnect, `/ignis reload`
- Nested tables require re-assignment (`data.nested = t`)

### Other

- `require "format"` — `format(template)`, `broadcastFormat(template)`
- `require "simple"` — `registerSimple(syntax, template, range?, overlay?)`
- `Vec(x, y, z)` constructor with arithmetic metamethods
