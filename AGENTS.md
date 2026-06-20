# PxIgnis — Agent instructions

Fabric mod (MC 1.21.11) — Lua scripting API for Minecraft server. Kotlin (compiler 2.2.21, runtime 2.3.21), Fabric Loom 1.16, Yarn mappings, Java 21.

## Build & test

```
./gradlew build         # CI does this
./gradlew test          # unit tests only, no MC runtime
./gradlew runServer
```

- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj`.
- Build prints 5× `Cannot remap children/literals/command/requirement…` — cosmetic, safe to ignore.
- Composite build: `includeBuild 'pxluanova'` in `settings.gradle`.
- CI: `.github/workflows/build.yml` — `./gradlew build --no-daemon` on push/PR to `main`.

## Testing quirks

`src/test/kotlin/ru/pyxiion/ignis/` — JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`. Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` does NOT call `MetaTableRegistry.init()` — triggers MC bootstrap and crashes. Tests read pre-existing metatables directly.

## Architecture

**EventBus** (`EventBus.kt`) — unified handler map. Both `mc.on()` and `region:on()` use it. `on(event, callback, throttle?)` returns int ID. `off(id)` removes by ID. `fire()` error-isolates handlers. Throttle via `tick()`.
`mc.emit(event, ...)` fires custom events from Lua (`LuaCmdLoader.kt`).

**Shared metatables** — one `LuaTable` metatable per wrapper type. Companion objects expose `initMeta(meta)` that sets up `__index`/`__newindex` via `rawset`. `MetaTableRegistry.init()` creates fresh metatables (order: vec→entity→player→world→structure→item→inventory→container→sidebar→mob→hologram→region).

`toLuaValue()` creates a fresh `LuaTable`, `setmetatable(MetaTableRegistry.X)`, rawsets `__pxrp_type` + `__pxrp_object` (+ `__pxrp_data` for per-instance state). Methods on the shared metatable read `__pxrp_object`.

**Player wrapper cache** — `LuaMcApi` owns `mutableMapOf<UUID, LuaValue>()`. `mc.players` (property) and `world.players` share this cache. `PlayerListWrapper` wraps a source function with per-tick caching. Invalidated per-player on `DISCONNECT`.

**Wrapper inheritance** — `PlayerWrapper.__index` falls through to `ENTITY.__index`. Same for `MobWrapper` and `HologramWrapper`.

**Tags/pos lazy-cached** — first `__index` access creates a proxy table and `rawset`s it; subsequent reads bypass `__index`.

**Regions** — `RegionManager` singleton. `Region.bus` is per-region `EventBus`. Entity tracking via mixins. Destroyed on reload (`closeAll()`).

## Conventions & gotchas

- `Utils.kt` (root) and `types/Utils.kt` are separate files with distinct helpers.
- `luaToNbt`/`nbtToLua` are in root `Utils.kt`. Do NOT duplicate.
- After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Always use `rawset` for new keys.
- `__index`/`__newindex` via Lua `:` syntax: arg(1) is `self`, actual params start at arg(2).
- `resolveBlockId` auto-prepends `minecraft:` if no namespace present.
- `ItemStackWrapper.unwrap()` always calls `copy()` — never leak raw references.
- `__pxrp_data` pattern: per-instance state as userdata on the Lua table. Never use companion `object` statics — they survive reload.
- **Data persistence**: `DataTable` (a `LuaTable` subclass) lazy-loads from `config/ignis/storage/` JSON. `saveAll()` on reload and server stop. No functions/userdata/threads, only string/int keys.
- **PxLuaNova subproject**: `pxluanova/AGENTS.md` has its own agent instructions.

## Reload sequence

`LuaCmdLoader.reload()` order:
`storageManager.saveAll()` → fire `uninit` → clear all managers → `prepareGlobals()` → load & execute Lua scripts → fire `init` → `registerAll()`.

## Lua environment

- **Runtime**: PxLuaNova (Lua 5.2), composite build from `pxluanova/`
- **Config dir**: `config/ignis/` (`.lua` files sorted alphabetically). Falls back to `config/ignis.lua`. First run copies `demo.lua` from resources.
- **`package.path`**: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`
- **Loaded**: `math`, `string`, `table`, `bit32`, `package`, base. **Not loaded**: `io`, `os`, `coroutine`, `debug`
- **Reload** tears down and rebuilds all globals. Persistent state: `mc.data`, `player.data`, or storage.
- **Scheduler**: ticked via `ServerTickEvents.END_SERVER_TICK`. Tasks cleared on reload and server stop.
- **Built-in libs** (in resources, loaded via `require`): `format`, `simple`, `chestgui`.

**Lambda syntax** (opt-in: `--# nova syntax` on line 1):
  - `\{ x, y -> x + y }` → `function(x, y) return x + y end`
  - `\{ return 42 }` — single-expression body, implicit return
  - Trailing block: `register("x") \{ ctx -> ... }` desugars to second arg. Trailing bodies are chunks (no implicit return).
  - `\{` is a two-char lexer token. Bare `\` outside string = syntax error.

## Quick references

| Topic | File |
|---|---|
| Event list (mc.on) | [`PxIgnis.kt`](src/main/java/ru/pyxiion/ignis/PxIgnis.kt) |
| Register syntax + types | [`LuaCmdLoader.kt`](src/main/java/ru/pyxiion/ignis/LuaCmdLoader.kt) |
| mc.\* API surface | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt) |

## Project layout

```
src/main/java/ru/pyxiion/ignis/
  PxIgnis.kt            # lifecycle + event wiring (no event mixins)
  LuaCmdLoader.kt        # runtime, register(), reload()
  LuaCommandManager.kt   # Brigadier tree
  CommandSyntax.kt       # SyntaxParser, buildVariants
  EventBus.kt            # Unified event bus
  Scheduler.kt           # mc.schedule / mc.scheduleRepeating
  Utils.kt               # luaTableOf, permissions, luaToNbt/nbtToLua
  api/                   # 23 wrappers (Player, Entity, World, Vector, Mob, Hologram,
  │                      #   Inventory, Container, Sidebar, Structure, ItemStack, etc.)
  types/                 # LuaArgumentType, ChoiceArgumentType, toLuaValue()
  storage/               # DataTable, DataBackend, JsonBackend, StorageManager
  mixins/                # 7 mixins (CommandNode, Entity, Server, Screen, etc.)
```
