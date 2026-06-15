# PxIgnis — Agent instructions

Fabric mod (MC 1.21.11) — Lua scripting API for Minecraft server. Kotlin 2.3.21 (plugin), Fabric Loom 1.16, Yarn, Java 21.

## Build, test, run

```
./gradlew build          # compiles + runs all tests (CI verifies)
./gradlew test           # unit tests only, no Minecraft runtime
./gradlew runServer      # or runClient
```

- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj` AND `me.lucko.fabric.permissions.api` → `ru.pyxiion.lib.permissions`
- Access widener `pxignis.accesswidener` is empty
- Build prints 5× `Cannot remap children/literals/command/requirement…` — cosmetic, safe to ignore
- `run/` is gitignored

## CI

`.github/workflows/build.yml` — `./gradlew build` on push/PR to `main`; tag `v*` creates GitHub release + Modrinth publish (jar excluding `-all` and `-sources`).

## Testing

`src/test/kotlin/ru/pyxiion/ignis/` — 7 files: `SyntaxParserTest`, `BuildVariantsTest`, `BrigadierTreeTest`, `EventManagerTest`, `MetaTableRegistryTest`, `DataTableTest`, `HologramManagerTest`. JUnit 5 via `kotlin-test-junit5`. Pure logic, no Minecraft runtime.

`BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns a non-Map type on this classpath. Use `childrenField.get(node) as Map<*, *>`.

## 0.9.0 changes worth knowing

- **`LuaMixinManager.kt` deleted** → `mc.observeHook`/`removeHook`/`clearHooks` are **completely removed**, not just WIP. Use `mc.on()` events.
- **`player_join` split**: cancellable `player_join_init` (early, player not fully loaded) + new `player_join` (post-load, not cancellable). Old scripts listening on `player_join` fire on `player_join_init` automatically (backwards compatible).
- **Output jar ~839 KB** (was ~4.9 MB with ByteBuddy).

## Shared metatables pattern

Wrappers use **shared metatables** (one per type). Each wrapper companion has an `initMeta(meta: LuaTable)` function that sets up `__index`/`__newindex`/`__pairs` + methods via `rawset` on the metatable. `MetaTableRegistry.init()` creates fresh meta tables (wiping user modifications) and calls init in order: vec → entity → player → world → mob → structure → inventory → container → sidebar → mob → hologram.

`toLuaValue()` creates only a fresh data table with `setmetatable(MetaTableRegistry.X)` and rawsets `__pxrp_type` + `__pxrp_object`. Methods and lazy proxies are on the shared metatable — they read `__pxrp_object` from the data table via `rawget`.

| File | Companions `initMeta` |
|---|---|
| `EntityWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 8 methods |
| `PlayerWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 13 methods (incl. `hasPermission`) |
| `WorldWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 9 methods |
| `MobWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 10 methods |
| `StructureWrapper.kt` | `__index`, `__pairs`, + `place` method |
| `InvWrapper.kt` | `__index`, `__pairs`, + 5 methods |
| `ContainerWrapper.kt` | `__index`, `__pairs`, + 2 methods |
| `SidebarWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 4 methods (`setLine`, `show`, `hide`, `destroy`). Exposed via `player.sidebar` smart property — config table assignment creates/updates, reading returns sidebar object |
| `HologramWrapper.kt` | `__index`, `__newindex`, `__pairs`, + 2 methods (`setLine`, `destroy`). Falls through to `ENTITY.__index` for `pos`, `uuid`, `world`, etc. Wraps `DisplayEntity.TextDisplayEntity` |

## Inventory / Container API

`LockableInventory` extends `SimpleInventory` with a `locked` flag. When locked, `removeStack()` returns `ItemStack.EMPTY` and `clear()` is a no-op — `setStack()` is always allowed. `InvWrapper.setItem`/`fill`/`clear` call `unlocked {}` to bypass the lock when Lua scripts modify inventory contents.

`ContainerManager` is a singleton that tracks open `ScreenHandler` → `ContainerWrapper` mappings. `ContainerManager.shouldAllowClick()` fires the Lua callback and returns `false` to cancel via mixin. All containers are force-closed on `/ignis reload` and player disconnect.

When `container:onClick(fn)` registers a callback, the inventory is automatically locked. When `onClick(nil)` is called, the inventory unlocks (free item movement, for shared inventories).

## Sidebar API

`player.sidebar` is a smart read-write property that manages the per-player sidebar. Uses a **local `Scoreboard()` instance** + direct packet sending — does NOT touch the server's global scoreboard, so other players never see it.

**Assignment** (via `__newindex` on PlayerWrapper):
- `player.sidebar = { title = "X", lines = {"a", "b"} }` — creates or updates sidebar, merges with existing, auto-shows on first creation
- `player.sidebar = { title = "X" }` — update only the title
- `player.sidebar = { lines = {"a"} }` — update only the lines
- `player.sidebar = { visible = false }` — hide the sidebar
- `player.sidebar = nil` — destroy the sidebar

**Properties** on the returned object (read via `__index`, write via `__newindex`):
- `sb.title` / `sb.title = "..."` — current title (sends update packet if visible)
- `sb.lines` / `sb.lines = {"a", "b"}` — current lines table / replace all lines
- `sb.visible` — whether the sidebar is shown
- `sb.lineCount` — number of lines

**Methods**: `sb:setLine(n, text)`, `sb:show()`, `sb:hide()`, `sb:destroy()`.

`SidebarManager` is a singleton that tracks active sidebars. All sidebars are destroyed on `/ignis reload` and per-player on `DISCONNECT`.

## Hologram API

`world:spawnHologram(pos, text, opts?)` creates a `DisplayEntity.TextDisplayEntity` (vanilla `minecraft:text_display`) and returns a hologram wrapper. Holograms are **global** — visible to all players in range of the entity (no per-player scope).

**Properties** (r/w via `__index`/`__newindex`):
- `holo.text` — full text string, `\n` for line breaks
- `holo.lines` — array table of lines (split/join on `\n`)
- `holo.alignment` — `"left"`, `"center"`, `"right"`
- `holo.billboard` — `"fixed"`, `"vertical"`, `"horizontal"`, `"center"`
- `holo.lineWidth`, `holo.background`, `holo.opacity`, `holo.shadow`, `holo.seeThrough`, `holo.glowing`

**Methods**: `holo:setLine(n, text)`, `holo:destroy()`.

Falls through to `ENTITY.__index` for `pos` (lazy live proxy), `uuid`, `world`, `removed`, `tags`, `readNbt`, `writeNbt`, etc.

`HologramManager` is a singleton that tracks all live holograms (keyed by entity UUID). `closeAll()` is called on `/ignis reload`. No per-player cleanup — holograms are not player-owned. Global holograms use vanilla entity replication — no custom packets.

## Conventions

- **Russian error messages** in logs and chat — do not flag as bugs
- **`Utils.kt` (root)** and **`types/Utils.kt`** are separate files with distinct helpers
- **`luaToNbt`/`nbtToLua`** are in root `Utils.kt`. Do NOT duplicate.
- **`rawset` vs `set`**: After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Methods and data writes use `t.rawset(key, value)`. Property writes route through `__newindex`. Any key added after `setmetatable` MUST use `rawset`.
- **`__index`/`__newindex` arg indexing**: When called via Lua `:` syntax, arg(1) is `self`, actual params start at arg(2). Shared metatable methods (`spawn`, `setBlock`, etc.) use `args.arg(2)`+ for this reason.
- **`minecraft:` auto-prefix**: `resolveBlockId` in WorldWrapper.kt adds `minecraft:` if no namespace present. Used by block methods and `world:spawn()`.
- **ItemStack mutation shield**: `ItemStackWrapper.unwrap()` always calls `copy()`. Never leak raw references to Lua.
- **Fresh nodes per variant**: `ArgDef` stores only `luaType` + `isOptional`. `LuaCommandManager.addCommand` creates fresh `ArgumentCommandNode` instances on demand.

## Internals

- **Tags/pos lazy-cached**: First access via `__index` creates a proxy table and `rawset`s it on the data table. Subsequent accesses find it directly (bypass `__index`).
- **Player wrapper cache**: `LuaMcApi` maintains `mutableMapOf<UUID, LuaValue>()` — `mc.players()` and `world.players` reuse cached wrappers. Invalidated on `DISCONNECT`.
- **Player extends entity lookup**: `PlayerWrapper`'s shared metatable falls through to `ENTITY.__index` for entity properties. No separate `EntityWrapper` instantiation per player.
- **Permission propagation**: Parent literal nodes require OR of their children's permissions. Nil permission → unrestricted.
- **Container cleanup**: `ContainerManager.closeAll()` called on `/ignis reload` and per-player on `DISCONNECT`. Prevents item theft when Lua callbacks are lost.
- **`vecTable(x, y, z)` helper** (Vector.kt): Creates `{x, y, z}` LuaTable with `MetaTableRegistry.VEC` metatable set. `internal`.
- **`resolveOperand(v)` helper** (Vector.kt): Extracts `(x, y, z)` from a vector table or a scalar (replicated to all 3 axes). Used by binary operator metamethods and `world:particle()`.
- **`mc.fetch`/`mc.sleep`**: Coroutine-yielding async operations in `AsyncLib.kt`. `mc.fetch` uses Java `HttpClient.sendAsync`, yields the Lua coroutine, resumes on server thread with a response table (shared `RESPONSE_META` metatable, lazy `.json` via `__index`). `mc.sleep(ticks)` uses `Scheduler.schedule` to resume after delay. Both are cleared on `/ignis reload` (new LuaState = new coroutines).
- **Response metatable**: Shared singleton `RESPONSE_META` in `AsyncLib` companion. Not in `MetaTableRegistry` — managed entirely in `AsyncLib.kt`, reset on each `install()` call via `responseMetaReset()`.
- **Mob AI**: `MobAIManager` tracks per-mob behaviour scripts via mixins (`MobEntityMixin`, `LivingEntityMixin`). `LuaGoal` extends vanilla `Goal` with full movement/look/jump control. `MobWrapper` exposes `setAI`/`clearAI`/`navigateTo`/`lookAt`/`tryAttack`/`canSee`/`distanceTo`/etc. to Lua.

## Project layout

```
src/main/java/ru/pyxiion/ignis/
  PxIgnis.kt               # lifecycle + ALL event wiring (no mixins for events)
  LuaCmdLoader.kt           # Lua runtime, register() bridge, type map, reload sequence
  LuaCommandManager.kt      # dynamic Brigadier tree management
  CommandSyntax.kt          # SyntaxParser, buildVariants
  LuaEventManager.kt        # mc.on() event bus
  Scheduler.kt              # mc.schedule/mc.scheduleRepeating/mc.cancelTask
  Utils.kt                  # luaTableOf(), checkPermission(), asVarArgFunction(), toVec3d(),
                            #   toBlockPos(), luaToNbt(), nbtToLua()
  api/
    AsyncLib.kt             # mc.fetch() + mc.sleep(): coroutine-based HTTP + sleep async
    PlayerWrapper.kt        # Lua-facing player wrapper (shared metatable, delegates to ENTITY.__index)
    EntityWrapper.kt        # Universal entity wrapper (shared metatable + initMeta)
    WorldWrapper.kt         # ServerWorld wrapper — particle(), buildParticleEffect() via codec
    MobWrapper.kt           # MobEntity wrapper — setAI/clearAI/navigateTo/tryAttack/etc.
    MobAIManager.kt         # Singleton tracker for per-mob behaviour scripts + mixin hooks
    LuaGoal.kt              # Vanilla Goal subclass used by MobAIManager (MOVEMENT/LOOK/JUMP controls)
    LuaMcApi.kt             # mc table factory
    StructureWrapper.kt     # Structure template wrapper
    ItemStackWrapper.kt     # ItemStack ↔ LuaTable conversion
    Vector.kt               # Vec(x,y,z) Lua constructor + arithmetic metatable
    MetaTableRegistry.kt    # mc.getMetatable() — 11 singleton LuaTables, delegates init to wrappers
    InvWrapper.kt           # SimpleInventory wrapper — getItem, setItem, fill, clear, open()
    ContainerWrapper.kt     # Per-player open screen session — close(), onClick(), player, inventory
    ContainerManager.kt     # Singleton tracker for open containers + LockableInventory
    SidebarWrapper.kt       # Per-player sidebar — title/lines properties + show/hide/destroy/setLine
    SidebarManager.kt       # Singleton tracker for active sidebars, cleanup on disconnect/reload
    HologramWrapper.kt      # Global hologram — text/lines/alignment/billboard/etc. + setLine/destroy. Wraps TextDisplayEntity
    HologramManager.kt      # Singleton tracker for live holograms, cleanup on reload
    Raycast.kt              # performRaycast() — shared by entity and world raycast methods
  types/
    LuaArgumentType.kt      # Interface for Brigadier arg adapters
    ChoiceArgumentType.kt   # StringArgumentType.word() + runtime validation + SuggestionProvider
    Utils.kt                # toLuaValue() — Any→LuaValue coercion
  storage/                  # DataTable, DataBackend, JsonBackend, StorageManager
  mixins/
    CommandNodeMixin.java       # @Accessor on Brigadier CommandNode
    MinecraftServerMixin        # @Inject on reloadResources → luaLoader.reload()
    ScreenHandlerMixin.java     # @Inject on onSlotClick + onClosed
    StructureTemplateMixin      # @Accessor on StructureTemplate.entities
    MobEntityMixin.java         # @Inject on tick → MobAIManager per-mob behaviour dispatch
    LivingEntityMixin.java      # @Inject on tick → MobAIManager per-mob behaviour dispatch
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

**Reserved commands** (blocked by `addCommand`): `ignis`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist`.

## Events

All events are Fabric API callbacks wired in `PxIgnis.kt` — no mixins for events.

| Lua event | Cancellable |
|-----------|:-----------:|
| `server_start` | ❌ |
| `server_stop` | ❌ |
| `player_join_init` | ✅ (early, player not fully loaded) |
| `player_join` | ❌ (post-load, player fully ready) |
| `player_leave` | ❌ |
| `player_respawn` | ❌ |
| `player_death` | ❌ |
| `player_chat` | ✅ |
| `player_block_break` | ✅ |
| `player_block_place` | ✅ (only when held item is BlockItem) |
| `player_use_item` | ✅ |
| `player_attack_entity` | ✅ |
| `player_interact_entity` | ✅ |
| `player_hurt` | ✅ |
| `entity_hurt` | ✅ |
| `player_damage` | ❌ |
| `entity_damage` | ❌ |
| `player_kill` | ❌ |
| `entity_spawn` | ❌ |
| `entity_despawn` | ❌ |
| `entity_death` | ✅ |

Cancellable events: return `false` to cancel.

## Storage

- JSON at `config/ignis/storage/global.json` and `config/ignis/storage/players/<uuid>.json`
- Atomic writes via temp file + atomic move
- DataTable validates types (no cyclic refs, functions, userdata, threads)
- Nested table writes (`data.nested.key = v`) persist directly on save
- Saved on: server stop, player disconnect, `/ignis reload`. Per-player data removed from storage map on disconnect.

## Scheduler & Lua environment

- Ticked via `ServerTickEvents.END_SERVER_TICK` → `Scheduler.tick()`
- Delay/interval in ticks (20 ticks = 1 sec)
- `mc.cancelTask(id)` — returns `false` if `id >= nextId` (never scheduled) or already cancelled
- All tasks cleared on `/ignis reload` and server stop
- Runtime: PxLuaNova (`com.pxluanova:pxluanova-jse:3.1.0`, Lua 5.2 targeting) — included as composite build from `pxluanova/`
- Config dir: `config/ignis/` (all `.lua` alphabetically). Falls back to `config/ignis.lua`. First run creates `demo.lua` from resource.
- `package.path`: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`
- Loaded std libs: `math`, `string`, `table`, `bit32`, `package`, base lib. **Not loaded**: `io`, `os`, `coroutine`, `debug`
- Reload completely tears down and rebuilds globals — all global Lua state is lost. Persistent state must use `mc.data`, `player.data`, or external storage.
- `require "format"` → `format(template)` / `broadcastFormat(template)`
- `require "simple"` → `registerSimple(syntax, template, range?, overlay?)`
- `require "chestgui"` → `chestgui.create(rows, title)` → `gui:set(row,col,item,cb)` / `gui:decorate(row,col,item)` / `gui:open(player)`

## PxLuaNova subproject

`pxluanova/` is a Gradle composite build (3 subprojects). Modified LuaJ fork with virtual-thread coroutines.

```
./gradlew -p pxluanova build                       # build all modules
./gradlew -p pxluanova :pxluanova-core:build        # core only
./gradlew -p pxluanova test                         # run all tests
```

| Module | Description |
|--------|-------------|
| `pxluanova-core` | Interpreter, compiler, std libs (`org.luaj.vm2.*`) |
| `pxluanova-jse` | Java SE platform, luajava, LuaJC bytecode compiler |
| `pxluanova-test` | JUnit 4 test suite |

Use `includeBuild 'pxluanova'` in root `settings.gradle` — dependencies `com.pxluanova:pxluanova-*:3.1.0` auto-resolve to project outputs. Local reference repos (`luaj-upstream/`, `wagyourtail-luaj/`, `cobalt-upstream/`) are gitignored in `pxluanova/.gitignore`.

## Documentation site

Astro + Starlight site in `site/`. Brand: **PxIgnis**, domain `ignis.pyxiion.ru`.

```
site/
├── astro.config.mjs
├── package.json
├── src/
│   ├── styles/custom.css       # theme variables (no MDX <style> blocks)
│   ├── pages/index.astro        # marketing landing page
│   └── content/docs/
│       ├── guide/               # getting-started, 01-your-first-command
│       ├── reference/           # 16 API/event/storage doc pages
│       ├── libraries/           # overview, format, simple, chestgui
│       └── examples/            # basic-commands, events, persistence
└── dist/                        # static output (gitignored)
```

Commands:
```
cd site
npm run dev       # dev server at localhost:4321
npm run build     # static output to dist/
npm run preview   # preview built site
```

- Starlight config: array-style `social`, `customCss` (camelCase), `editLink`. 26 doc pages.
- CSS in MDX `<style>` blocks breaks MDX parsing — styles go in `custom.css` instead.
- Deploy: copy `dist/` to web root (nginx/Caddy).

### API docs formatting rules

| Rule | Convention |
|------|-----------|
| **Sidebar** | Two sections: **Core API** (mc.*, Async, Events, Storage) and **Types** (Player, World, Entity, etc.) |
| **Frontmatter title** | Matches sidebar label exactly. Core API keeps `API` suffix (`mc.* API`, `Async API`). Types drop it (`Player`, `World`, `Entity`, `ItemStack`, `Inventory`, `Container`, `Vector`, `Structure`, `Sidebar`, `Hologram`, `Mob AI`). |
| **`---` rules** | Never in page body — only YAML frontmatter delimiters (lines 1 and 4). Starlight theme already spaces `##` sections. |
| **Properties** | Each property is a `### \`name\`` block. `**type:**` line with type in backticks. Description explains read/write in prose. Wrapper types link to their doc page. |
| **Methods** | `### \`methodName(args)\`` block. Short description. `- param (\`type\`) — desc` list for params. Lua code block. Param table only for 3+ params with defaults. |
| **Intro** | Consistent first sentence: `"The {name} wrapper provides access to a Minecraft {thing}."` for Types, `"The {name} table is the entry point for..."` or similar for Core API. |
| **Cross-refs** | Inline links in intro or at end of section. mc-api stubs for `mc.on`/`mc.data` link to dedicated Events/Storage pages — no duplication. |
