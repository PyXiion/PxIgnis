# PxIgnis — Agent instructions

Fabric mod (MC 1.21.11) — Lua scripting API for Minecraft server. Kotlin (compiler 2.2.21, runtime 2.3.21), Fabric Loom 1.16, Yarn mappings, Java 21.

## Build, test, run

```
./gradlew build             # compiles + 8 test files (~115 unit tests), CI does this
./gradlew test              # unit tests only, no Minecraft runtime
./gradlew runServer         # or runClient
```

- **Shadow relocates**: `org.luaj` → `ru.pyxiion.lib.luaj` only (NOT permissions).
- **Build prints 5× `Cannot remap children/literals/command/requirement…`** — cosmetic, safe to ignore.
- **Access widener** `pxignis.accesswidener` is empty.
- `run/` and `site/dist/` are gitignored.
- Composite build: `includeBuild 'pxluanova'` in `settings.gradle` — auto-resolves `com.pxluanova:pxluanova-*:3.1.0` to subproject outputs.

## CI

`.github/workflows/build.yml` — `./gradlew build --no-daemon` on push/PR to `main`; tag `v*` creates GitHub release + Modrinth publish (jar excludes `-all` and `-sources`).

## Testing

`src/test/kotlin/ru/pyxiion/ignis/` — 8 files, JUnit 5 via `kotlin-test-junit5`. Pure logic, no Minecraft runtime.

`BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`. Use `childrenField.get(node) as Map<*, *>`.

`MetaTableRegistryTest` does NOT call `MetaTableRegistry.init()` — that triggers Minecraft bootstrap and crashes. Tests read pre-existing metatables directly.

## Key architecture

**EventBus** (not LuaEventManager) — unified handler map in `EventBus.kt`. Both `mc.on()` and `region:on()` use it. `on(event, callback, throttle?)` returns int ID. `off(id)` removes by ID, returns boolean. `hasHandlers(event)` checks before fire. `fire()` error-isolates handlers (one Lua error doesn't crash others). Decrement throttle counter via `tick()`.

**Shared metatables** — one `LuaTable` metatable per wrapper type. Companion objects expose `initMeta(meta)` that sets up `__index`/`__newindex`/`__pairs` + methods via `rawset`. `MetaTableRegistry.init()` (init order: vec → entity → player → world → structure → item → inventory → container → sidebar → mob → hologram → region) creates fresh metatables wiping user modifications.

`toLuaValue()` creates a fresh `LuaTable`, calls `setmetatable(MetaTableRegistry.X)`, rawsets `__pxrp_type` + `__pxrp_object` (+ `__pxrp_data` for per-instance state like player cache, tick provider). Methods and lazy proxies are on the shared metatable — they read `__pxrp_object` from the data table.

**Player wrapper cache** — `LuaMcApi` owns `mutableMapOf<UUID, LuaValue>()`. `mc.players` (property, not function) and `world.players` share this cache. `PlayerListWrapper` wraps a source function (server player list or world-specific players) with per-tick caching: first read in a server tick builds the `LuaTable`; subsequent reads in the same tick return the cached instance. Invalidated per-player on `DISCONNECT`.

**Player extends entity** — `PlayerWrapper.__index` falls through to `ENTITY.__index` for entity properties. `MobWrapper` and `HologramWrapper` do the same.

**Tags/pos lazy-cached** — first access via `__index` creates a proxy table and `rawset`s it on the data table; subsequent reads find it directly bypassing `__index`.

**Regions** — `RegionManager` singleton owns `regionsByWorld`, `regionsByChunk` (chunk-indexed for O(1) pre-filter), `regionsById`, `tickSubscribers`. `Region.bus` is per-region `EventBus`. Entity tracking via `EntityMixin.java` (mixin on `Entity.baseTick` → `RegionManager.onEntityMoved`), `ENTITY_LOAD`/`ENTITY_UNLOAD` events, and `AFTER_DEATH`. Regions are destroyed on reload (`closeAll()`).

## Conventions & gotchas

- **Russian error messages** in logs and chat — not bugs
- **`Utils.kt` (root)** and **`types/Utils.kt`** are separate files with distinct helpers
- **`luaToNbt`/`nbtToLua`** are in root `Utils.kt`. Do NOT duplicate.
- **`rawset` vs `set`**: after `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Methods and data writes use `t.rawset(key, value)`. Any key added after `setmetatable` MUST use `rawset`.
- **`__index`/`__newindex` arg indexing**: when called via Lua `:` syntax, arg(1) is `self`, actual params start at arg(2). Shared metatable methods use `args.arg(2)+`.
- **`minecraft:` auto-prefix**: `resolveBlockId` in WorldWrapper.kt adds `minecraft:` if no namespace present. Used by block methods and `world:spawn()`.
- **ItemStack mutation shield**: `ItemStackWrapper.unwrap()` always calls `copy()`. Never leak raw references to Lua.
- **`__pxrp_data` pattern**: wrappers that need per-instance state (e.g. `WorldWrapper` with player cache + tick provider) store it as a userdata holder on the Lua table. The companion `__index` reads it back via `rawget`. Do NOT use companion `object` statics for instance data — they survive reload and cause stale references.

## Project layout

```
src/main/java/ru/pyxiion/ignis/
  PxIgnis.kt               # lifecycle + ALL event wiring (no mixins for events)
  LuaCmdLoader.kt           # Lua runtime, register() bridge, type map, reload sequence
  LuaCommandManager.kt      # dynamic Brigadier tree management
  CommandSyntax.kt          # SyntaxParser, buildVariants
  EventBus.kt               # Unified event bus (mc.on, region:on, handler IDs, throttle)
  Scheduler.kt              # mc.schedule/mc.scheduleRepeating/mc.cancelTask
  Utils.kt                  # luaTableOf(), checkPermission(), asVarArgFunction(), toVec3d(),
                            #   toBlockPos(), luaToNbt(), nbtToLua()
  api/
    LuaMcApi.kt             # mc table factory
    PlayerWrapper.kt        # Lua-facing player, delegates to ENTITY.__index
    EntityWrapper.kt        # Universal entity wrapper
    WorldWrapper.kt         # ServerWorld wrapper
    Vector.kt               # vec(x,y,z) Lua constructor + arithmetic metatable
    MobWrapper.kt           # MobEntity wrapper — setAI/clearAI/navigateTo/tryAttack/etc.
    MobAIManager.kt         # Singleton tracker for per-mob behaviour scripts + mixin hooks
    LuaGoal.kt              # Vanilla Goal subclass (MOVEMENT/LOOK/JUMP controls)
    HologramWrapper.kt      # TextDisplayEntity wrapper
    HologramManager.kt      # Singleton tracker for live holograms
    InvWrapper.kt           # SimpleInventory wrapper
    ContainerWrapper.kt     # Per-player open screen session
    ContainerManager.kt     # Singleton tracker for open containers + LockableInventory
    SidebarWrapper.kt       # Per-player sidebar — local Scoreboard + direct packets
    SidebarManager.kt       # Singleton tracker for active sidebars
    StructureWrapper.kt     # Structure template wrapper
    ItemStackWrapper.kt     # ItemStack ↔ LuaTable conversion
    MetaTableRegistry.kt    # 12 singleton metatables, delegates init to wrappers
    AsyncLib.kt             # mc.fetch + mc.sleep (coroutine-yielding async)
    PlayerListWrapper.kt    # Per-tick cached player list array (shared cache with LuaMcApi)
    Region.kt               # Region domain model + RegionManager singleton
    RegionWrapper.kt        # Region Lua wrapper
    Raycast.kt              # performRaycast() — shared by entity and world raycast
  types/
    LuaArgumentType.kt      # Brigadier arg adapter interface
    ChoiceArgumentType.kt   # StringArgumentType.word() + validation + suggestions
    Utils.kt                # toLuaValue() — Any→LuaValue coercion
  storage/                  # DataTable, DataBackend, JsonBackend, StorageManager
  mixins/
    CommandNodeMixin.java       # @Accessor on Brigadier CommandNode
    EntityMixin.java            # @Inject on baseTick → RegionManager entity tracking
    MinecraftServerMixin        # @Inject on reloadResources → luaLoader.reload()
    ScreenHandlerMixin.java     # @Inject on onSlotClick + onClosed
    StructureTemplateMixin      # @Accessor on StructureTemplate.entities
    MobEntityMixin.java         # @Inject on tick → MobAIManager behaviour dispatch
    LivingEntityMixin.java      # @Inject on tick → MobAIManager behaviour dispatch
```

## Lua environment

- **Runtime**: PxLuaNova (`com.pxluanova:pxluanova-jse:3.1.0`, Lua 5.2) — composite build from `pxluanova/`
- **Config dir**: `config/ignis/` (all `.lua` alphabetically). Falls back to `config/ignis.lua`. First run creates `demo.lua` + `demo_ai.lua`.
- **`package.path`**: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`
- **Loaded std libs**: `math`, `string`, `table`, `bit32`, `package`, base. **Not loaded**: `io`, `os`, `coroutine`, `debug`
- **Reload** tears down and rebuilds all globals. Persistent state must use `mc.data`, `player.data`, or storage.
- **Scheduler**: ticked via `ServerTickEvents.END_SERVER_TICK` → `Scheduler.tick()`. Delay/interval in ticks (20 = 1 sec). All tasks cleared on reload and server stop.
- **Built-in libs**: `require "format"`, `require "simple"`, `require "chestgui"`.

**Lambda syntax** (PxLuaNova extension, opt-in per file):
- Add `--# nova syntax` on line 1 of a script to enable lambda syntax for that file.
- `\{ x, y -> x + y }` — named-arg lambda; desugars to `function(x, y) return x + y end`.
- `\{ return 42 }` — zero-arg single-expression body; the expression is implicitly returned.
- `\{ if x then return 1 end; return 2 }` — zero-arg chunk body; uses explicit `return` (also works for args form).
- `register("lambda") \{ ctx -> ctx.player:send("hi") }` — trailing block on a call; desugars to `register("lambda", function(ctx) ctx.player:send("hi") end)`. Trailing bodies are always parsed as chunks (no implicit return); use `return` for a value.
- `\{` is **not** a Lua token; the lexer recognizes the two-char sequence. A bare `\` outside a string is a hard syntax error.

## Global events (mc.on)

All wired in `PxIgnis.kt` via Fabric API — no mixins for events. `mc.on(name, fn, opts?)` returns int ID. `mc.off(id)` returns boolean.

**Cancellable** (return `false`): `player_join_init`, `player_chat`, `player_block_break`, `player_block_place`, `player_use_item`, `player_attack_entity`, `player_interact_entity`, `player_hurt`, `entity_hurt`, `entity_death`.

**Non-cancellable**: `server_start`, `init`, `server_stop`, `uninit`, `player_join`, `player_leave`, `player_respawn`, `player_death`, `player_damage`, `entity_damage`, `player_kill`, `entity_spawn`, `entity_despawn`, `tick` (with throttle).

## Register syntax

```lua
register("cmd <name:type> [<name:type>]", handler, permission?)
```

Types: `text`, `word`, `player` (alias `target`), `int`, `double`, `float`, `bool`, `block_pos`, `choice=opt1,opt2,...`

**Reserved** (blocked): `ignis`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist`.

## Documentation site

Astro + Starlight in `site/`. Domain `ignis.pyxiion.ru`.

```
cd site
npm run dev       # dev server at localhost:4321
npm run build     # static output to dist/
npm run preview   # preview built site
```

- Starlight config: array-style `social`, `customCss` (camelCase). No MDX `<style>` blocks — use `custom.css`.
- Deploy: copy `dist/` to web root (nginx/Caddy).
