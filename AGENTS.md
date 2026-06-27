# PxIgnis — Agent instructions

Fabric mod — Lua scripting API for Minecraft server. Kotlin 2.3.21, Fabric Loom 1.16, Java 21.
Entry points: `PxIgnis.kt` (ModInitializer) → `IgnisRuntime.kt` (orchestrator).
Subproject `pxluanova/` has its own `AGENTS.md`.

## Bumping version

Update `mod_version` in `gradle.properties`, the badge in `README.md:3`, add changelog entry in
`site/src/content/docs/changelog.md`, commit & tag `v<version>`.

**Do NOT commit version bump or changelog without user review first.** Stage the files and let the user decide when to
commit and tag.

## Mappings

Uses Mojang official mappings. No yarn layer.

### Class/package renames (Mojang ← Yarn)

| Mojang class                                                                           | Yarn class                                                         |
|----------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `net.minecraft.commands.CommandSourceStack`                                            | `net.minecraft.server.command.ServerCommandSource`                 |
| `net.minecraft.commands.Commands`                                                      | `net.minecraft.server.command.CommandManager`                      |
| `net.minecraft.commands.CommandSource`                                                 | `net.minecraft.command.CommandSource`                              |
| `net.minecraft.network.chat.Component`                                                 | `net.minecraft.text.Text`                                          |
| `net.minecraft.resources.ResourceLocation`                                             | `net.minecraft.util.Identifier`                                    |
| `net.minecraft.core.BlockPos`                                                          | `net.minecraft.util.math.BlockPos`                                 |
| `net.minecraft.world.phys.Vec3`                                                        | `net.minecraft.util.math.Vec3d`                                    |
| `net.minecraft.world.phys.AABB`                                                        | `net.minecraft.util.math.Box`                                      |
| `net.minecraft.server.level.ServerPlayer`                                              | `net.minecraft.server.network.ServerPlayerEntity`                  |
| `net.minecraft.server.level.ServerLevel`                                               | `net.minecraft.server.world.ServerWorld`                           |
| `net.minecraft.world.entity.Entity`                                                    | `net.minecraft.entity.Entity`                                      |
| `net.minecraft.world.entity.LivingEntity`                                              | `net.minecraft.entity.LivingEntity`                                |
| `net.minecraft.world.entity.player.Player`                                             | `net.minecraft.entity.player.PlayerEntity`                         |
| `net.minecraft.world.entity.mob.Mob`                                                   | `net.minecraft.entity.mob.MobEntity`                               |
| `net.minecraft.world.entity.item.ItemEntity`                                           | `net.minecraft.entity.ItemEntity`                                  |
| `net.minecraft.world.entity.EquipmentSlot`                                             | `net.minecraft.entity.EquipmentSlot`                               |
| `net.minecraft.world.entity.Display`                                                   | `net.minecraft.entity.decoration.DisplayEntity`                    |
| `net.minecraft.world.entity.Display\$TextDisplay`                                      | `net.minecraft.entity.decoration.DisplayEntity\$TextDisplayEntity` |
| `net.minecraft.world.entity.EntityType`                                                | `net.minecraft.entity.EntityType`                                  |
| `net.minecraft.world.entity.EntityDimensions`                                          | `net.minecraft.entity.EntityDimensions`                            |
| `net.minecraft.world.entity.EntitySpawnReason`                                         | `net.minecraft.entity.SpawnReason`                                 |
| `net.minecraft.world.entity.ai.Brain`                                                  | `net.minecraft.entity.ai.brain.Brain`                              |
| `net.minecraft.world.entity.ai.goal.Goal`                                              | `net.minecraft.entity.ai.goal.Goal`                                |
| `net.minecraft.world.entity.ai.goal.GoalSelector`                                      | `net.minecraft.entity.ai.goal.GoalSelector`                        |
| `net.minecraft.world.entity.ai.attributes.Attributes`                                  | `net.minecraft.entity.attribute.EntityAttributes`                  |
| `net.minecraft.world.effect.MobEffectInstance`                                         | `net.minecraft.entity.effect.StatusEffectInstance`                 |
| `net.minecraft.nbt.CompoundTag`                                                        | `net.minecraft.nbt.NbtCompound`                                    |
| `net.minecraft.nbt.DoubleTag`                                                          | `net.minecraft.nbt.NbtDouble`                                      |
| `net.minecraft.nbt.ListTag`                                                            | `net.minecraft.nbt.NbtList`                                        |
| `net.minecraft.nbt.StreamTagVisitor`                                                   | `net.minecraft.nbt.NbtSizeTracker`                                 |
| `net.minecraft.server.network.ServerGamePacketListenerImpl`                            | `net.minecraft.server.network.ServerPlayNetworkHandler`            |
| `net.minecraft.server.level.ServerBossEvent`                                           | `net.minecraft.entity.boss.ServerBossBar`                          |
| `net.minecraft.world.BossEvent`                                                        | `net.minecraft.entity.boss.BossBar`                                |
| `net.minecraft.world.entity.player.Inventory`                                          | `net.minecraft.entity.player.PlayerInventory`                      |
| `net.minecraft.world.inventory.AbstractContainerMenu`                                  | `net.minecraft.screen.ScreenHandler`                               |
| `net.minecraft.world.inventory.MenuType`                                               | `net.minecraft.screen.ScreenHandlerType`                           |
| `net.minecraft.world.inventory.ChestMenu`                                              | `net.minecraft.screen.GenericContainerScreenHandler`               |
| `net.minecraft.world.inventory.ClickType`                                              | `net.minecraft.screen.slot.SlotActionType`                         |
| `net.minecraft.world.SimpleContainer`                                                  | `net.minecraft.inventory.SimpleInventory`                          |
| `net.minecraft.world.SimpleMenuProvider`                                               | `net.minecraft.screen.NamedScreenHandlerFactory`                   |
| `net.minecraft.world.level.Level`                                                      | `net.minecraft.world.World`                                        |
| `net.minecraft.world.level.block.Block`                                                | `net.minecraft.block.Block`                                        |
| `net.minecraft.world.level.block.state.BlockState`                                     | `net.minecraft.block.BlockState`                                   |
| `net.minecraft.world.level.block.Mirror`                                               | `net.minecraft.util.BlockMirror`                                   |
| `net.minecraft.world.level.block.Rotation`                                             | `net.minecraft.util.BlockRotation`                                 |
| `net.minecraft.world.level.border.WorldBorder`                                         | `net.minecraft.world.border.WorldBorder`                           |
| `net.minecraft.world.level.GameType`                                                   | `net.minecraft.world.GameMode`                                     |
| `net.minecraft.world.level.ClipContext`                                                | `net.minecraft.world.RaycastContext`                               |
| `net.minecraft.world.level.ChunkPos`                                                   | `net.minecraft.util.math.ChunkPos`                                 |
| `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate`        | `net.minecraft.structure.StructureTemplate`                        |
| `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager` | `net.minecraft.structure.StructurePlacementData`                   |
| `net.minecraft.world.phys.shapes.CollisionContext`                                     | `net.minecraft.block.ShapeContext`                                 |
| `net.minecraft.world.phys.HitResult`                                                   | `net.minecraft.util.hit.HitResult`                                 |
| `net.minecraft.world.phys.BlockHitResult`                                              | `net.minecraft.util.hit.BlockHitResult`                            |
| `net.minecraft.world.phys.EntityHitResult`                                             | `net.minecraft.util.hit.EntityHitResult`                           |
| `net.minecraft.world.InteractionResult`                                                | `net.minecraft.util.ActionResult`                                  |
| `net.minecraft.world.InteractionHand`                                                  | `net.minecraft.util.Hand`                                          |
| `net.minecraft.world.item.ItemStack`                                                   | `net.minecraft.item.ItemStack`                                     |
| `net.minecraft.core.component.DataComponents`                                          | `net.minecraft.component.DataComponentTypes`                       |
| `net.minecraft.core.component.DataComponentType`                                       | `net.minecraft.component.DataComponentType`                        |
| `net.minecraft.core.Registry`                                                          | `net.minecraft.registry.Registry`                                  |
| `net.minecraft.core.registries.BuiltInRegistries`                                      | `net.minecraft.registry.Registries`                                |
| `net.minecraft.resources.ResourceKey`                                                  | `net.minecraft.registry.RegistryKey`                               |
| `net.minecraft.resources.ResourceLocation`                                             | `net.minecraft.util.Identifier`                                    |
| `net.minecraft.core.particles.ParticleType`                                            | `net.minecraft.particle.ParticleType`                              |
| `net.minecraft.core.particles.ParticleTypes`                                           | `net.minecraft.particle.ParticleTypes`                             |
| `net.minecraft.core.particles.ParticleEffect`                                          | `net.minecraft.particle.ParticleEffect`                            |
| `net.minecraft.world.level.block.state.properties.BooleanProperty`                     | `net.minecraft.state.property.BooleanProperty`                     |
| `net.minecraft.world.level.block.state.properties.IntProperty`                         | `net.minecraft.state.property.IntProperty`                         |
| `net.minecraft.world.level.block.state.properties.Property`                            | `net.minecraft.state.property.Property`                            |
| `net.minecraft.world.scores.Scoreboard`                                                | `net.minecraft.scoreboard.Scoreboard`                              |
| `net.minecraft.world.scores.Criterion`                                                 | `net.minecraft.scoreboard.ScoreboardCriterion`                     |
| `net.minecraft.world.scores.DisplaySlot`                                               | `net.minecraft.scoreboard.ScoreboardDisplaySlot`                   |
| `net.minecraft.world.scores.Objective`                                                 | `net.minecraft.scoreboard.ScoreboardObjective`                     |
| `net.minecraft.sounds.SoundSource`                                                     | `net.minecraft.sound.SoundCategory`                                |
| `net.minecraft.sounds.SoundEvent`                                                      | `net.minecraft.sound.SoundEvent`                                   |
| `net.minecraft.util.Mth`                                                               | `net.minecraft.util.math.MathHelper`                               |
| `net.minecraft.resources.ResourceLocation`                                             | `net.minecraft.util.Identifier`                                    |

### Method/field renames (selected)

| Mojang                                     | Yarn                                             |
|--------------------------------------------|--------------------------------------------------|
| `send(Packet)`                             | `sendPacket(Packet)`                             |
| `connection` (field)                       | `networkHandler` (field)                         |
| `level` (method on Entity)                 | `entityWorld` (method on Entity)                 |
| `position()` (method on Entity)            | `entityPos` (method on Entity)                   |
| `getItemInHand(InteractionHand)`           | `getStackInHand(Hand)`                           |
| `getItemBySlot(EquipmentSlot)`             | `getEquippedStack(EquipmentSlot)`                |
| `getMainHandItem()`                        | `getMainHandStack()`                             |
| `isClientSide()`                           | `isClient()`                                     |
| `distanceToSqr(Vec3)`                      | `squaredDistanceTo(Vec3d)`                       |
| `containerMenu` (field on Player)          | `currentScreenHandler` (field on PlayerEntity)   |
| `carried` (field on AbstractContainerMenu) | `cursorStack` (field on ScreenHandler)           |
| `broadcastChanges()`                       | `sendContentUpdates()`                           |
| `slots` (field on AbstractContainerMenu)   | `slots` (field on ScreenHandler)                 |
| `selected` (field on Inventory)            | `selectedSlot` (field on PlayerInventory)        |
| `getSelected()` (method on Inventory)      | `getSelectedStack()` (method on PlayerInventory) |

## Build & test

```
./gradlew build -PtargetVersion=1.21.10   # build for 1.21.10
./gradlew build                           # build for 1.21.11 (default)
./gradlew test                            # unit tests only, no MC runtime
./gradlew runServer
```

Two MC versions (`-PtargetVersion=1.21.10` / `1.21.11`); version-specific code lives in `src/version-*/kotlin/`.
CI: `.github/workflows/build.yml` — both versions on push/PR to `main`; auto-publishes to Modrinth on tag push.

## Testing quirks

`src/test/kotlin/ru/pyxiion/ignis/` — JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`.
  Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` must NOT call `MetaTableRegistry.init()` — that triggers MC bootstrap and crashes. Tests read
  pre-existing metatables directly.

## Conventions & gotchas

- `Utils.kt` (root) and `types/Utils.kt` are separate files with distinct helpers.
- After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Always use `rawset` for new keys.
- `__index`/`__newindex` via Lua `:` syntax: arg(1) is `self`, actual params start at arg(2).
- `resolveBlockId` auto-prepends `minecraft:` if no namespace present.
- `ItemStackWrap`: `wrap()` stores the raw `ItemStack` as `__pxrp_object`; property setters (`item.count = 5`) mutate it
  in
  place. `ItemStackWrap.unwrap()` (and the `:copy()` method) call `.copy()` — so a Lua-side assignment like
  `player2.mainhand = player1.mainhand` is safe, but `unwrap()` consumers must not assume the returned stack is
  the same instance as the source.
- Per-instance wrapper state (e.g. `WorldWrap`'s `InstanceData` with `playerCache` + `tickProvider`) lives on
  `__pxrp_data` userdata, not on Kotlin `companion object` fields. The shared `BUILT` metatable template on
  `companion object` IS the right place for shared/constant data — it must survive reload.
- `mc.sleep(ticks)` / `mc.fetch(url)` coroutine-yielding async is NOT available in event handlers; use
  `mc.schedule(0, fn)`.

## Lua environment → see `agent_docs/lua.md`

Loaded libs, `package.path`, globals, lambda syntax, scheduler tick, built-in `require` libs (`format`, `simple`,
`chestgui`).

## API surface (site reference)

When writing scripts, prefer linking to docs over source code.
When updating the API, always ask user if he wants to update the documentation (site) & lua-types (<project>/lua-types/*
.lua).

| Topic                     | File                                                                                              |
|---------------------------|---------------------------------------------------------------------------------------------------|
| All events (mc.on)        | [`PxIgnis.kt`](src/main/java/ru/pyxiion/ignis/PxIgnis.kt) (also `/reference/events` in site docs) |
| mc.\* API                 | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt)                                   |
| register() syntax + types | [`CommandSyntax.kt`](src/main/java/ru/pyxiion/ignis/commands/CommandSyntax.kt)                    |
| **Full docs**             | **ignis.pyxiion.ru**                                                                              |

`register("syntax", function(ctx))` does NOT have `ctx.args`. It uses positional args.
For `register("cmd <arg1:word> <arg2:player>", handler)` handler is `(ctx, arg1, arg2)`.
