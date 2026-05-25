# PxRP — Agent instructions

Fabric mod (MC 1.21.11) — server admins define chat commands via Lua. Kotlin 2.2.21, Fabric Loom 1.16, Yarn, Java 21.

## Build, test, run

```
./gradlew build          # compiles + runs all tests (CI verifies this)
./gradlew test           # unit tests only, no Minecraft runtime
./gradlew runServer      # or runClient
```

- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj`
- Access widener `pxrp.accesswidener` is empty
- Build prints 5× `Cannot remap children/literals/command/requirement…` — cosmetic, safe to ignore
- `run/` is gitignored

## CI

`.github/workflows/build.yml` — `./gradlew build` on push/PR to `main`; tag push `v*` creates GitHub release + Modrinth publish (jar excluding `-all` and `-sources`).

## Testing

`src/test/kotlin/ru/pyxiion/pxrp/` — JUnit 5 via `kotlin-test-junit5`. 3 files: `SyntaxParserTest`, `BuildVariantsTest`, `BrigadierTreeTest`. Pure logic, no Minecraft runtime.

`BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns a non-Map type on this classpath. Use `childrenField.get(node) as Map<*, *>`.

## Project layout

```
src/main/java/ru/pyxiion/pxrp/
  PxRp.kt               # lifecycle + event wiring
  LuaCmdLoader.kt        # Lua runtime, register() bridge, type map
  LuaCommandManager.kt   # dynamic Brigadier tree management
  CommandSyntax.kt       # SyntaxParser, buildVariants — standalone, no Minecraft deps
  LuaEventManager.kt     # mc.on() event bus
  Scheduler.kt           # mc.schedule/mc.scheduleRepeating/mc.cancelTask
  Utils.kt               # luaTableOf(), checkPermission(), asVarArgFunction(), toVec3d(), toBlockPos()
  api/
    Player.kt            # Lua-facing Player wrapper (delegates to EntityWrapper)
    EntityWrapper.kt     # Universal entity wrapper — properties, tags proxy, equipment
    World.kt             # ServerWorld wrapper — spawn, setBlock, getBlock, fill, time/weather
    LuaMcApi.kt          # mc table factory — particles, sounds, broadcast, time, schedule, world(name), players(), onlineCount, player wrapper cache
    ItemStackWrapper.kt  # ItemStack ↔ LuaTable conversion, createItem factory, copy() on unwrap
    Vector.kt            # {x, y, z} Lua table helper
  types/
    LuaArgumentType.kt   # Interface for Brigadier arg type adapters
    ChoiceArgumentType.kt# StringArgumentType.word() + runtime validation + SuggestionProvider
    Utils.kt             # toLuaValue() — Any→LuaValue coercion
  storage/               # DataTable, DataBackend, JsonBackend, StorageManager
  mixins/
    CommandNodeMixin.java  # @Accessor on Brigadier CommandNode fields (children, command, requirement)
    MinecraftServerMixin   # @Inject on reloadResources → luaLoader.reload()
  coerce/                # DEAD CODE — entirely commented out
```

## Key conventions

- **Russian error messages** in logs and chat — do not flag as bugs
- **`Utils.kt` (root)** and **`types/Utils.kt`** are separate files with distinct helpers
- **`rawset` vs `set` on Player/World**: After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Methods and data writes use `t.rawset(key, value)`. Property writes route through `__newindex`. Any key added after `setmetatable` MUST use `rawset`.
- **`__index`/`__newindex` arg indexing**: When called via Lua `:` syntax, arg(1) is `self`, actual params start at arg(2). World companion methods (`spawn`, `setBlock`, `getBlock`, `fill`) use `args.arg(2)`+ for this reason.
- **Tags proxy must be cached**: `tagsTable(e)` creates a new proxy table. Storing via `t.rawset("tags", tagsProxy)` in `toLuaValue()` ensures the same table instance is returned every time. Accessing `player.tags` via `__index` creates ephemeral tables — writes appear to work but iterate over stale data.
- **Position coercion**: `toVec3d()` and `toBlockPos()` in root `Utils.kt` accept `{x, y, z}` or `{x=, y=, z=}` tables.
- **Player wrapper cache**: `LuaMcApi` maintains `mutableMapOf<UUID, LuaValue>()` — `mc.players()` and `world.players` (when World came from `mc.world(name)`) reuse cached wrappers. Invalidated on `DISCONNECT` via `api.invalidatePlayer(uuid)`. Allocated once per player join.
- **`minecraft:` auto-prefix**: `resolveBlockId` in World.kt adds `minecraft:` if no namespace present. Used by both block methods and `world:spawn()`.
- **ItemStack mutation shield**: `ItemStackWrapper.unwrap()` always calls `copy()`. Never leak raw references to Lua.
- **Encapsulated client sync**: Player equipment writes call `e.currentScreenHandler.sendContentUpdates()`. Non-player entity equipment uses `liv.equipStack()` (entity tracker handles sync).
- **Nil-mapping**: Lua `nil` → `ItemStack.EMPTY` or `null` — never crash where a default makes sense.
- **Player armour slots**: `player.head/chest/legs/feet` use `EquipmentSlot.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE)`.
- **Player hand slots**: `player.mainhand` = active hotbar slot, `player.offhand` = slot 40. Property writes route through Player's `__newindex` → `inventory.setStack()` + `sendContentUpdates()`.
- **Player wraps EntityWrapper**: `Player.toLuaValue()` creates an `EntityWrapper(e).toLuaValue()`, checks player-only keys first (food, gamemode, xp, etc.), then delegates to EntityWrapper's LuaTable via `entityValue.get(key)` / `entityValue.set(key, value)`.
- **Permission propagation**: Parent literal nodes require OR of their children's permissions. Nil permission → unrestricted.
- **Choice args**: `ChoiceArgumentType` implements `LuaArgumentType` (not Brigadier's `ArgumentType`). Uses `StringArgumentType.word()` + runtime validation in `getArg()`. `SuggestionProvider` provides tab completions. Different choice sets share the same class.
- **Reserved commands**: `pxrp`, `stop`, `reload`, `op`, `deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `save-all`, `save-on`, `save-off`, `whitelist` — blocked in `addCommand()`.
- **Fresh nodes per variant**: `ArgDef` stores only `luaType` + `isOptional`. `LuaCommandManager.addCommand` creates fresh `ArgumentCommandNode` instances on demand via `argDef.luaType.getBrigadierArgument(name)`.

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

**Types**: `text` (multi-word), `word` (single word), `player` (or `target` alias), `int`, `double`, `float`, `bool`, `block_pos` (returns `{x,y,z}`), `choice=opt1,opt2,...`

**Handler**: `function(ctx, arg1, arg2, ...)` — `ctx.player` is a live wrapper. `ctx.player.data` is per-player DataTable.

## World API (`player.world` / `mc.world(name)`)

`player.world` returns a World wrapper (not a string — use `player.world.name`).

**Properties**: `name` (ro), `time` (rw), `raining` (rw), `thundering` (rw), `players` (ro).
- `time` is game ticks (long, Lua number). `w.time = w.time - (w.time % 24000) + 6000` sets to noon.
- `raining`/`thundering` are booleans. Setting toggles weather via `ServerWorld.setWeather()`.

**Methods** (called with `:` syntax):
- `world:spawn(entityId, pos, {overrides?})` — returns `EntityWrapper` or `nil` on failure. `pos` via `toVec3d()`. Overrides: `health` (number, LivingEntity only — if it exceeds current max, `maxHealth` is raised to match), `custom_name` (string).
- `world:setBlock(pos, blockId)` — flag `0x03` (notify clients + update neighbors).
- `world:getBlock(pos)` → string like `"minecraft:stone"`.
- `world:fill(pos1, pos2, blockId)` — flag `0x02` (neighbors only, no block updates). Volume capped at 32,768 blocks.
- `world:particle(particle, x, y, z)` — spawns a particle at position visible to all players in that world.
- `world:broadcastInRange(text, x, y, z, range, overlay?)` — broadcasts text to players within range in that world.

## EntityWrapper

Returned by `world:spawn()`. Also backs `ctx.player` internally (player-only keys + delegation).

**Read-write properties**: `pos` (Vec3d table), `customName`, `fallDistance`, `fireTicks`, `glowing`, `invulnerable`, `isSneaking`, `isSprinting`, `air`, `health`, `maxHealth`, and 13 attribute properties (`speed`, `armor`, `armorToughness`, `attackDamage`, `attackSpeed`, `knockbackResistance`, `luck`, `stepHeight`, `blockBreakSpeed`, `gravity`, `scale`, `safeFallDistance`, `flyingSpeed`). Attribute writes modify the attribute instance's `baseValue`.

**Read-only properties**: `uuid`, `type`, `name`, `displayName`, `world`, `dir`, `bodyDir`, `maxAir`.

**Equipment properties** (rw): `mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`. For players, writes go through inventory + `sendContentUpdates()`. For non-player entities, uses `equipStack()` (entity tracker handles sync). Entities that don't support the slot (e.g. pig) return `nil` on read and silently ignore writes.

**Tags**: `entity.tags[tag] = true/false` — backed by `Entity.getCommandTags()`. Iterable via `pairs()`. Proxy table is cached and shared — not created fresh per access.

## Storage

- JSON at `config/pxrp/storage/global.json` and `config/pxrp/storage/players/<uuid>.json`
- Atomic writes via temp file + atomic move
- DataTable validates types (no cyclic refs, no functions/userdata/threads)
- Nested table assignments require re-assignment: `data.nested = t` not `data.nested.key = v`
- **Saved on**: server stop, player disconnect, `/pxrp reload`. Not saved on every write.

## Scheduler

- Ticked via `ServerTickEvents.END_SERVER_TICK` → `Scheduler.tick()`
- Delay/interval in ticks (20 ticks = 1 sec)
- `mc.cancelTask(id)` — returns `false` if `id >= nextId` (never scheduled) or already cancelled
- All tasks cleared on `/pxrp reload` and server stop
- Individual callback errors caught and logged

## Lua environment

- Runtime: `org.luaj:luaj-jse:3.0.1` (Lua 5.2 targeting)
- Config dir: `config/pxrp/` (all `.lua` alphabetically). Falls back to `config/pxrp.lua`. First run creates `demo.lua` from resource.
- `package.path`: `config/pxrp/?.lua;config/pxrp/?/init.lua;?.lua`
- Loaded std libs: `math`, `string`, `table`, `bit32`, `package`, base lib. **Not loaded**: `io`, `os`, `coroutine`, `debug`
- `require "format"` → `format(template)` / `broadcastFormat(template)`
- `require "simple"` → `registerSimple(syntax, template, range?, overlay?)`
