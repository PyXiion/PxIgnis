# PxRP — Agent instructions

## What it is

A Fabric mod (Minecraft 1.21.x) that lets server admins define chat commands via Lua scripts. Kotlin-based, single-module Gradle project.

## Build & run

```sh
./gradlew build               # produces remapped shadow jar
./gradlew runServer           # Fabric Loom dev server
./gradlew runClient           # Fabric Loom dev client
```

- Java 21, Kotlin 2.2.21, Fabric Loom 1.16
- Shadow plugin relocates `org.luaj` → `ru.pyxiion.lib.luaj`
- Access widener: `src/main/resources/pxrp.accesswidener` (empty v2 header — no entries yet)
- `run/` is gitignored (dev server/client output)

## Entrypoints

| Type | Class |
|------|-------|
| Mod initializer | `ru.pyxiion.pxrp.PxRp` |

## Project layout

```
src/main/
  java/ru/pyxiion/pxrp/
    PxRp.kt                  # ModInit — registers /pxrp reload, lifecycle hooks
    LuaCmdLoader.kt          # Loads Lua scripts, bridges register() calls to Brigadier
    LuaCommandManager.kt     # Manages the dynamic Brigadier command tree
    api/                     # Lua-facing API: Context, Player, Vector, LuaMcApi
    storage/                 # DataTable, DataBackend (interface), JsonBackend
    mixins/                  # CommandNodeMixin (accessors), MinecraftServerMixin (reload hook)
    types/                   # LuaArgumentType interface
  resources/
    pxrp.lua                 # Bundled default Lua config (copied to config/ on first run)
    format.lua               # F-string-like template engine (loaded via require)
    simple.lua               # registerSimple convenience wrapper
    pxrp.mixins.json
    pxrp.accesswidener
    fabric.mod.json
```

## Lua system

- Lua runtime: `org.luaj:luaj-jse:3.0.1`
- Config file at `config/pxrp.lua` (auto-created from bundled resource on first run)
- `/pxrp reload` re-executes all Lua scripts (via `MinecraftServerMixin` also hooks `reloadResources`)
- `register(path, args, handler, permission?)` registers a Brigadier command from Lua
- Supported arg types: `text` (free-form), `target` (player selector)
- Custom named args: `"msg:text"` syntax overrides auto-generated name
- **Handler receives `ctx` as first arg** — the old `player` global is NOT set. Use `ctx.player` instead.

## Storage

- JSON backend at `config/pxrp/storage/global.json` and `config/pxrp/storage/players/<uuid>.json`
- Atomic writes via temp file + move
- DataTable: lazy-loaded, validates no cyclic refs, no functions/userdata/threads
- Nested table assignments require re-assignment — `ctx.player.data.nested = t` not `ctx.player.data.nested.key = v`
- **Data is saved on**: server stop, player disconnect, `/pxrp reload` — never on every write (batching). See `PxRp.kt` lifecycle hooks and `StorageManager.saveAll()` / `removePlayerData()`.

## Lua API surfaces

| Global | Source |
|--------|--------|
| `mc` | `LuaMcApi.kt` — particle, playSound, broadcast, broadcastInRange, time, data |
| `register` | `LuaCmdLoader.kt` |
| `format`, `broadcastFormat` | `format.lua` (requires `"format"`) |
| `registerSimple` | `simple.lua` (requires `"simple"`) |

- `mc.data` is a `DataTable` (persistent global storage)
- `ctx.player.data` is also a `DataTable` (per-player storage)
- `mc.time()` returns epoch seconds as double

## Error messages

**Kotlin-side log and user-facing messages are in Russian** — do not assume they are bugs. The bundled `pxrp.lua` example file is bilingual (English + Russian).

## Notable conventions

- Mixin accessors on `CommandNode` (`getChildren`, `getLiterals`, `getCommand`, `setCommand`, `setRequirement`) used to dynamically patch the Brigadier tree at runtime
- `KotlinInstance.kt` and `coerce/` exist but `coerce/KotlinToLua.kt` is commented out — don't expect active coercion code
- No test directory or test dependencies found
- No CI workflow found
- `fabric.mod.json` uses `"minecraft": ">=1.21-"` for version range (expanded from `gradle.properties` at build time)
