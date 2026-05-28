# PxRP — Agent instructions

Fabric mod (MC 1.21.11) — Lua scripting API for Minecraft server. Kotlin 2.3.21, Fabric Loom 1.16, Yarn, Java 21.

## Build, test, run

```
./gradlew build          # compiles + runs all tests (CI verifies)
./gradlew test           # unit tests only, no Minecraft runtime
./gradlew runServer      # or runClient
```

- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj` AND `me.lucko.fabric.permissions.api` → `ru.pyxiion.lib.permissions`
- Access widener `pxrp.accesswidener` is empty
- Build prints 5× `Cannot remap children/literals/command/requirement…` — cosmetic, safe to ignore
- `run/` is gitignored

## CI

`.github/workflows/build.yml` — `./gradlew build` on push/PR to `main`; tag `v*` creates GitHub release + Modrinth publish (jar excluding `-all` and `-sources`).

## Testing

`src/test/kotlin/ru/pyxiion/pxrp/` — 5 files: `SyntaxParserTest`, `BuildVariantsTest`, `BrigadierTreeTest`, `EventManagerTest`, `MetaTableRegistryTest`. JUnit 5 via `kotlin-test-junit5`. Pure logic, no Minecraft runtime.

`BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns a non-Map type on this classpath. Use `childrenField.get(node) as Map<*, *>`.

## Shared metatables pattern

Wrappers use **shared metatables** (one per type). Each wrapper companion has an `initMeta(meta: LuaTable)` function that sets up `__index`/`__newindex`/`__pairs` + methods via `rawset` on the metatable. `MetaTableRegistry.init()` creates fresh meta tables (wiping user modifications) and calls init in order: vec → entity → player → world → structure → inventory → container.

`toLuaValue()` creates only a fresh data table with `setmetatable(MetaTableRegistry.X)` and rawsets `__pxrp_type` + `__pxrp_object`. Methods and lazy proxies (tags, pos, sidebar) are on the shared metatable — they read `__pxrp_object` from the data table via `rawget`.

| File | Companion `initMeta` | Rewrites |
|---|---|---|
| `EntityWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 8 methods | tags, pos are lazy-cached via `__index` |
| `PlayerWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 11 methods | Falls through to `ENTITY.__index` for entity props |
| `WorldWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 9 methods | `playerCache` is static companion field |
| `StructureWrapper.kt` | `__index`, `__pairs`, + `place` method | Gets world from `worldArg.rawget("__pxrp_object")` |
| `InvWrapper.kt` | `__index`, `__pairs`, + 5 methods | `open()` method creates ContainerWrapper via ContainerManager |
| `ContainerWrapper.kt` | `__index`, `__pairs`, + 2 methods | Stores `ContainerWrapper` instance as `__pxrp_object` userdata |

## Inventory / Container API

`LockableInventory` extends `SimpleInventory` with a `locked` flag. When locked, `removeStack()` returns `ItemStack.EMPTY` and `clear()` is a no-op — `setStack()` is always allowed. `InvWrapper.setItem`/`fill`/`clear` call `unlocked {}` to bypass the lock when Lua scripts modify inventory contents.

`ContainerManager` is a singleton that tracks open `ScreenHandler` → `ContainerWrapper` mappings. `ContainerManager.shouldAllowClick()` fires the Lua callback and returns `false` to cancel via mixin. All containers are force-closed on `/pxrp reload` and player disconnect.

**Dual-layer click prevention:**
1. **Mixin** — `ScreenHandlerMixin.java` injects `@Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)` on `ScreenHandler`. When the Lua callback returns `false`, `ci.cancel()` skips the entire click body.
2. **LockableInventory** — `removeStack()` returns `ItemStack.EMPTY` when locked (safety net if cancel somehow fails).

When `container:onClick(fn)` registers a callback, the inventory is automatically locked. When `onClick(nil)` is called, the inventory unlocks (free item movement, for shared inventories).

## Key conventions

- **Russian error messages** in logs and chat — do not flag as bugs
- **`Utils.kt` (root)** and **`types/Utils.kt`** are separate files with distinct helpers
- **`luaToNbt`/`nbtToLua`** are in root `Utils.kt`. Do NOT duplicate.
- **`vecTable(x, y, z)` helper** (Vector.kt): Creates `{x, y, z}` LuaTable with `MetaTableRegistry.VEC` metatable set. `internal`.
- **`resolveOperand(v)` helper** (Vector.kt): Extracts `(x, y, z)` from a vector table or a scalar (replicated to all 3 axes). Used by binary operator metamethods and `world:particle()`.
- **`rawset` vs `set`**: After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Methods and data writes use `t.rawset(key, value)`. Property writes route through `__newindex`. Any key added after `setmetatable` MUST use `rawset`.
- **`__index`/`__newindex` arg indexing**: When called via Lua `:` syntax, arg(1) is `self`, actual params start at arg(2). Shared metatable methods (`spawn`, `setBlock`, etc.) use `args.arg(2)`+ for this reason.
- **Tags/pos/sidebar lazy-cached**: First access via `__index` creates a proxy table and `rawset`s it on the data table. Subsequent accesses find it directly (bypass `__index`).
- **Player wrapper cache**: `LuaMcApi` maintains `mutableMapOf<UUID, LuaValue>()` — `mc.players()` and `world.players` reuse cached wrappers. Invalidated on `DISCONNECT`.
- **`minecraft:` auto-prefix**: `resolveBlockId` in WorldWrapper.kt adds `minecraft:` if no namespace present. Used by block methods and `world:spawn()`.
- **ItemStack mutation shield**: `ItemStackWrapper.unwrap()` always calls `copy()`. Never leak raw references to Lua.
- **Player extends entity lookup**: `PlayerWrapper`'s shared metatable falls through to `ENTITY.__index` for entity properties. No separate `EntityWrapper` instantiation per player.
- **Permission propagation**: Parent literal nodes require OR of their children's permissions. Nil permission → unrestricted.
- **Fresh nodes per variant**: `ArgDef` stores only `luaType` + `isOptional`. `LuaCommandManager.addCommand` creates fresh `ArgumentCommandNode` instances on demand.
- **Container cleanup**: `ContainerManager.closeAll()` called on `/pxrp reload` and per-player on `DISCONNECT`. Prevents item theft when Lua callbacks are lost.

## Project layout

```
src/main/java/ru/pyxiion/pxrp/
  PxRp.kt               # lifecycle + ALL event wiring (no mixins for events)
  LuaCmdLoader.kt        # Lua runtime, register() bridge, type map, reload sequence
  LuaCommandManager.kt   # dynamic Brigadier tree management
  CommandSyntax.kt       # SyntaxParser, buildVariants
  LuaEventManager.kt     # mc.on() event bus
  Scheduler.kt           # mc.schedule/mc.scheduleRepeating/mc.cancelTask
  Utils.kt               # luaTableOf(), checkPermission(), asVarArgFunction(), toVec3d(), toBlockPos(), checkstringlist(), luaToNbt(), nbtToLua()
   api/
     PlayerWrapper.kt      # Lua-facing player wrapper (shared metatable, delegates to ENTITY.__index)
     EntityWrapper.kt      # Universal entity wrapper (shared metatable + initMeta)
     WorldWrapper.kt       # ServerWorld wrapper — particle(), buildParticleEffect() via codec
     LuaMcApi.kt           # mc table factory
     StructureWrapper.kt   # Structure template wrapper (no server param needed)
     ItemStackWrapper.kt   # ItemStack ↔ LuaTable conversion
     Vector.kt             # Vec(x,y,z) Lua constructor + arithmetic metatable
     PersonalSidebar.kt    # Per-player scoreboard sidebar (packet-based)
     MetaTableRegistry.kt  # mc.getMetatable() — 8 singleton LuaTables, delegates init to wrappers
     InvWrapper.kt         # SimpleInventory wrapper — getItem, setItem, fill, clear, open()
     ContainerWrapper.kt   # Per-player open screen session — close(), onClick(), player, inventory
     ContainerManager.kt   # Singleton tracker for open containers + LockableInventory
     Raycast.kt            # performRaycast() — shared by entity and world raycast methods
   types/
     LuaArgumentType.kt   # Interface for Brigadier arg adapters
     ChoiceArgumentType.kt# StringArgumentType.word() + runtime validation + SuggestionProvider
     Utils.kt             # toLuaValue() — Any→LuaValue coercion
   storage/               # DataTable, DataBackend, JsonBackend, StorageManager
   mixins/
     CommandNodeMixin.java    # @Accessor on Brigadier CommandNode children/literals/command/requirement
     MinecraftServerMixin     # @Inject on reloadResources → luaLoader.reload()
     ScreenHandlerMixin.java  # @Inject on onSlotClick(HEAD,cancellable) + onClosed(HEAD)
     StructureTemplateMixin   # @Accessor on StructureTemplate.entities
   coerce/                  # DEAD CODE — entirely commented out
```

## Register syntax

```lua
register("cmd <name:type> [<name:type>]", handler, permission?)
```

| Part | Meaning |
|------|---------|
| `cmd` `sub` | Literal path tokens |
| `<name:type>` | Required argument. Missing `:type` raises parse error |
| `[<name:type>]` | Optional trailing argument. Everything from first `[...]` onward is optional. Missing → `nil` |
| `<name:choice=x,y>` | Choice type — runtime validation, tab completions |

**Types**: `text`, `word`, `player` (alias `target`), `int`, `double`, `float`, `bool`, `block_pos`, `choice=opt1,opt2,...`

**Reserved commands** (blocked by `addCommand`): `pxrp`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist`.

## Events — all Fabric API callbacks, no mixins in PxRp.kt

| Lua event | Fabric callback | Cancellable |
|-----------|-----------------|:-----------:|
| `server_start` | `SERVER_STARTED` | ❌ |
| `server_stop` | `SERVER_STOPPING` | ❌ |
| `player_join` | `ServerPlayConnectionEvents.INIT` | ✅ |
| `player_leave` | `DISCONNECT` | ❌ |
| `player_death` | `AFTER_DEATH` (LivingEntity) | ❌ |
| `player_chat` | `ALLOW_CHAT_MESSAGE` | ✅ |
| `player_block_break` | `PlayerBlockBreakEvents.BEFORE` | ✅ |
| `player_block_place` | `UseBlockCallback.EVENT` (only when held item is BlockItem) | ✅ |
| `player_use_item` | `UseItemCallback.EVENT` | ✅ |
| `player_attack_entity` | `AttackEntityCallback.EVENT` | ✅ |
| `player_interact_entity` | `UseEntityCallback.EVENT` | ✅ |
| `player_hurt` | `ALLOW_DAMAGE` (players) | ✅ |
| `entity_hurt` | `ALLOW_DAMAGE` (non-players) | ✅ |
| `player_damage` | `AFTER_DAMAGE` (players) | ❌ |
| `entity_damage` | `AFTER_DAMAGE` (non-players) | ❌ |
| `player_kill` | `AFTER_KILLED_OTHER_ENTITY` | ❌ |

Cancellable events: return `false` to cancel.

## Storage

- JSON at `config/pxrp/storage/global.json` and `config/pxrp/storage/players/<uuid>.json`
- Atomic writes via temp file + atomic move
- DataTable validates types (no cyclic refs, functions, userdata, threads)
- Nested table assignments require re-assignment: `data.nested = t` not `data.nested.key = v`
- Saved on: server stop, player disconnect, `/pxrp reload`. Per-player data removed from storage map on disconnect.

## Scheduler

- Ticked via `ServerTickEvents.END_SERVER_TICK` → `Scheduler.tick()`
- Delay/interval in ticks (20 ticks = 1 sec)
- `mc.cancelTask(id)` — returns `false` if `id >= nextId` (never scheduled) or already cancelled
- All tasks cleared on `/pxrp reload` and server stop

## Lua environment

- Runtime: `org.luaj:luaj-jse:3.0.1` (Lua 5.2 targeting)
- Config dir: `config/pxrp/` (all `.lua` alphabetically). Falls back to `config/pxrp.lua`. First run creates `demo.lua` from resource.
- `package.path`: `config/pxrp/?.lua;config/pxrp/?/init.lua;?.lua`
- Loaded std libs: `math`, `string`, `table`, `bit32`, `package`, base lib. **Not loaded**: `io`, `os`, `coroutine`, `debug`
- Reload completely tears down and rebuilds globals — all global Lua state is lost. Persistent state must use `mc.data`, `player.data`, or external storage.
- `require "format"` → `format(template)` / `broadcastFormat(template)`
- `require "simple"` → `registerSimple(syntax, template, range?, overlay?)`
- `require "chestgui"` → `chestgui.create(rows, title)` → `gui:set(row,col,item,cb)` / `gui:decorate(row,col,item)` / `gui:open(player)`

## `world:particle()` implementation notes

- `particle()` accepts `(id, vec(pos), {count?, spread=vec(dx,dy,dz)?, speed?, data={...}?})` — no legacy positional `x,y,z` args.
- `buildParticleEffect()` uses Minecraft's codec system (`type.codec.codec().parse(ops, nbt)`) instead of per-type `when` branches. Uses shared `luaToNbt()` from `Utils.kt`.
- `normalizeData()` maps Lua sugar keys (`block`→`block_state` with `minecraft:` prefix, `item`→`{id, count}` compound, `color`/`fromColor`/`toColor`→packed int `0xRRGGBB`, `angle`→`roll`, camelCase→snake_case) before NBT conversion.
