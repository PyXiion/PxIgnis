# PxRP — Agent instructions

Fabric mod (MC 1.21.11) — server admins define chat commands via Lua. Kotlin 2.3.21, Fabric Loom 1.16, Yarn, Java 21.

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

`src/test/kotlin/ru/pyxiion/pxrp/` — 5 files: `SyntaxParserTest`, `BuildVariantsTest`, `BrigadierTreeTest`, `EventManagerTest`, `MetaTableRegistryTest`. JUnit 5 via `kotlin-test-junit5`. Pure logic, no Minecraft runtime.

`BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns a non-Map type on this classpath. Use `childrenField.get(node) as Map<*, *>`.

## Project layout

```
src/main/java/ru/pyxiion/pxrp/
  PxRp.kt               # lifecycle + ALL event wiring (no mixins for events)
  LuaCmdLoader.kt        # Lua runtime, register() bridge, type map, reload sequence
  LuaCommandManager.kt   # dynamic Brigadier tree management
  CommandSyntax.kt       # SyntaxParser, buildVariants — standalone, no Minecraft deps
  LuaEventManager.kt     # mc.on() event bus
  Scheduler.kt           # mc.schedule/mc.scheduleRepeating/mc.cancelTask
  Utils.kt               # luaTableOf(), checkPermission(), asVarArgFunction(), toVec3d(), toBlockPos()
   api/
     Player.kt            # Lua-facing Player wrapper (delegates to EntityWrapper)
     EntityWrapper.kt     # Universal entity wrapper — properties, tags proxy, equipment, readNbt/writeNbt, nbtToLua/luaToNbt, damage, setOnFireFor
     World.kt             # ServerWorld wrapper — spawn, setBlock, getBlock, fill, time/weather
     LuaMcApi.kt          # mc table factory — particles, sounds, broadcast, time, schedule, world(name), players(), onlineCount, loadStructure, loadStructureFile, dump, getMetatable
     StructureWrapper.kt  # Structure template wrapper — size, place(rotation/mirror/on_entity)
     ItemStackWrapper.kt  # ItemStack ↔ LuaTable conversion, createItem factory, copy() on unwrap
     Vector.kt            # {x, y, z} Lua table helper
     PersonalSidebar.kt   # Per-player scoreboard sidebar (packet-based, avoids global scoreboard)
     MetaTableRegistry.kt # Global Lua metatable store — mc.getMetatable() backed by 4 singletons
   types/
     LuaArgumentType.kt   # Interface for Brigadier arg type adapters
     ChoiceArgumentType.kt# StringArgumentType.word() + runtime validation + SuggestionProvider
     Utils.kt             # toLuaValue() — Any→LuaValue coercion
   storage/               # DataTable, DataBackend, JsonBackend, StorageManager
   mixins/
     CommandNodeMixin.java  # @Accessor on Brigadier CommandNode fields
     MinecraftServerMixin   # @Inject on reloadResources → luaLoader.reload()
     StructureTemplateMixin.java  # @Accessor on StructureTemplate.entities field
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
- **`__pairs` for all wrapper types**: `EntityWrapper`, `World`, `Player`, and `StructureWrapper` implement `__pairs` metamethods so `pairs()` works. Each returns a static key list + dynamic value lookup via `self.get(key)`. Uses `kotlin.collections.listOf(...)` to avoid clash with LuaJ's `VarArgFunction.listOf`.
- **`mc.dump(obj, depth?)`**: Recursive value printer with cycle detection via `System.identityHashCode()`. Respects `__pairs` metamethods. Prints to stdout and returns the string. Max depth defaults to 3.
- **`mc.getMetatable(name)`**: Returns one of 4 singleton LuaTables — `"player"`, `"entity"`, `"world"`, `"structure"`. Functions set on these are available on any wrapper of that type via `__index` fallthrough. Player metatable delegates to entity metatable (not vice versa). Colon-callable (receives self as arg1).
- **`player.sidebar`**: Proxy table backed by `PersonalSidebarManager` (packet-based per-player scoreboard). `player.sidebar = { title = "...", lines = {...} }` creates; `player.sidebar.title`/`player.sidebar.lines` update; `player.sidebar = nil` clears. Persists across worlds/reconnects (restored 2 ticks after join via scheduler delay).
- **Structure loading**: `StructureWrapper` stores `StructureTemplate` + `MinecraftServer`. `.place()` creates a fresh `StructurePlacementData` per call. When `on_entity` callback is provided, sets `ignoreEntities=true`, manually iterates `StructureTemplateMixin.entities`, transforms positions, loads entities via `EntityType.loadEntityWithPassengers()`, applies rotation/mirror transforms via `applyRotation()`/`applyMirror()`, clears UUIDs (auto-generated on spawn), calls the callback, and only spawns if callback doesn't return `false`. Without callback, uses standard `StructureTemplate.place()` with `ignoreEntities=false`.
- **`readNbt`/`writeNbt`**: Explicit serialization pair — no automatic `nbt` proxy. `readNbt` creates an `NbtWriteView`, calls `entity.saveData(writeView)`, retrieves compound via `.getNbt()`, converts to Lua table. `writeNbt` converts Lua table to `NbtCompound`, creates an `NbtReadView`, calls `entity.readData(readView)`. Uses Yarn 1.21.11's `NbtWriteView`/`NbtReadView` API with `ErrorReporter.EMPTY`.
- **NBT conversion**: `nbtToLua()` handles all 12 NBT element types. `luaToNbt()` detects array tables (int keys 1..n, no string keys) → `NbtList`, else `NbtCompound`. Booleans → `NbtByte`. Lua numbers: `isint()` → `NbtInt`, `islong()` → `NbtLong`, else `NbtDouble`. Throws on functions/userdata/threads.
- **Structure entity callback**: Entity UUIDs are explicitly removed from NBT before `loadEntityWithPassengers()` (auto-regenerated). Transform applies `applyRotation(rotation) + applyMirror(mirror) - entity.yaw` for yaw correction. Positions are transformed via `StructureTemplate.transformAround()` with pivot at `((size.x-1)/2, 0, (size.z-1)/2)`.
- **`entity:damage(amount, sourceEntity?)`**: Optional source entity enables knockback via `playerAttack()`/`mobAttack()`. Source is looked up via UUID from the Lua table. Falls back to `damageSources.generic()` (no knockback) when absent.
- **`entity:setOnFireFor(ticks)`**: Sets `e.fireTicks = ticks` AND immediately calls `e.setOnFire(true)` to sync the ON_FIRE flag via data tracker (bypasses 1-tick `baseTick()` delay). Use instead of writing `fireTicks` directly for instant client-side fire visual.
- **Wrapper `__index` fallback chain**: Key not found on the Lua table → `MetaTableRegistry` (player checks PLAYER then ENTITY; world checks WORLD; structure checks STRUCTURE). This is how `mc.getMetatable()` extensions work.

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

## Events (all Fabric API callbacks, no mixins)

| Lua event | Wired in `PxRp.kt` | Fires | Cancellable |
|-----------|--------------------|-------|:-----------:|
| `server_start` | `SERVER_STARTED` | After Lua reload | No |
| `server_stop` | `SERVER_STOPPING` | Before save | No |
| `player_join` | `ServerPlayConnectionEvents.INIT` | Player connecting | Yes (disconnects player) |
| `player_leave` | `DISCONNECT` | Player disconnects | No |
| `player_death` | `AFTER_DEATH` (LivingEntity) | Player dies | No |
| `player_chat` | `ALLOW_CHAT_MESSAGE` | Player sends message | Yes |
| `player_block_break` | `PlayerBlockBreakEvents.BEFORE` | Before block break | Yes |
| `player_block_place` | `UseBlockCallback.EVENT` | Before block place (only when held item is BlockItem) | Yes |
| `player_use_item` | `UseItemCallback.EVENT` | Right-click with item | Yes |
| `player_attack_entity` | `AttackEntityCallback.EVENT` | Left-click on entity | Yes |
| `player_interact_entity` | `UseEntityCallback.EVENT` | Right-click on entity | Yes |
| `player_hurt` | `ALLOW_DAMAGE` (players) | Before damage applied | Yes |
| `entity_hurt` | `ALLOW_DAMAGE` (non-players) | Before damage applied | Yes |
| `player_damage` | `AFTER_DAMAGE` (players) | After damage applied | No |
| `entity_damage` | `AFTER_DAMAGE` (non-players) | After damage applied | No |
| `player_kill` | `AFTER_KILLED_OTHER_ENTITY` | Player kills entity | No |

**Handler signatures**:
- `player_block_break(player, pos{x,y,z}, blockId)`
- `player_block_place(player, pos{x,y,z}, blockId)`
- `player_use_item(player, hand, itemStack, itemId)` — hand is `"main"`/`"off"`, itemStack is wrapper or nil
- `player_attack_entity(player, entity)` / `player_interact_entity(player, entity)`
- `player_hurt(player, damageType, amount)` / `entity_hurt(entity, damageType, amount)` — pre-damage (cancellable)
- `player_damage(player, damageType, damageTaken, blocked)` / `entity_damage(entity, damageType, damageTaken, blocked)` — post-damage
- `player_death(player, damageType)` — damageType is last segment after `.` (e.g. `"fall"`, `"player_attack"`)
- `player_kill(player, killedEntity, damageType)`

Cancellable events: return `false` to cancel the action.

## Storage

- JSON at `config/pxrp/storage/global.json` and `config/pxrp/storage/players/<uuid>.json`
- Atomic writes via temp file + atomic move
- DataTable validates types (no cyclic refs, no functions/userdata/threads)
- Nested table assignments require re-assignment: `data.nested = t` not `data.nested.key = v`
- **Saved on**: server stop, player disconnect, `/pxrp reload`. Not saved on every write.
- Per-player data removed from storage map on disconnect

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
- Reload completely tears down and rebuilds globals — all global Lua state is lost. Persistent state must use `mc.data`, `player.data`, or external storage.
