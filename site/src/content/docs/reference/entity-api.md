---
title: Entity
description: Lua wrapper for Minecraft entities — properties, attributes, equipment, effects, and raycasting.
---

The entity wrapper provides access to any Minecraft entity. Player entities extend this
wrapper — see [Player API](/docs/reference/player-api).

Metatable name: `"entity"`

## Properties

### `entity.uuid`

**type:** `string`

UUID string. Read-only.

### `entity.type`

**type:** `string`

Entity type (e.g. `"minecraft:zombie"`). Read-only.

### `entity.name`

**type:** `string`

Entity name. Read-only.

### `entity.displayName`

**type:** `string`

Display name. Read-only.

### `entity.customName`

**type:** `string` or `nil`

Custom name. Assign to set.

### `entity.world`

**type:** [`World`](/docs/reference/world-api)

The world the entity is in. Read-only.

### `entity.pos`

**type:** `table` (`{x, y, z}`)

Position. Assign to teleport.

### `entity.dir`

**type:** `table` (`{x, y, z}`)

Look direction. Read-only.

### `entity.bodyDir`

**type:** `table` (`{x, y, z}`)

Body direction. Read-only.

### `entity.isSneaking`

**type:** `boolean`

Sneaking state. Assign to set.

### `entity.isSprinting`

**type:** `boolean`

Sprinting state. Assign to set.

### `entity.fallDistance`

**type:** `number`

Current fall distance. Assign to set.

### `entity.removed`

**type:** `boolean`

Whether the entity has been removed. Read-only.

### `entity.health`

**type:** `number`

Current health. Assign to set.

### `entity.maxHealth`

**type:** `number`

Maximum health. Assign to set base value; clamps current health.

### `entity.air`

**type:** `number`

Remaining air. Assign to set.

### `entity.maxAir`

**type:** `number`

Maximum air. Read-only.

### `entity.fireTicks`

**type:** `number`

Remaining fire ticks. `-1` means not on fire. Assign to set.

### `entity.glowing`

**type:** `boolean`

Glowing effect state. Assign to set.

### `entity.invulnerable`

**type:** `boolean`

Invulnerability state. Assign to set.

## Equipment

### `entity.mainhand`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Main hand item. Assign to set equipped item.

### `entity.offhand`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Off hand item. Assign to set equipped item.

### `entity.head`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Helmet item. Assign to set equipped item.

### `entity.chest`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Chestplate item. Assign to set equipped item.

### `entity.legs`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Leggings item. Assign to set equipped item.

### `entity.feet`

**type:** [`ItemStack`](/docs/reference/itemstack-api) or `nil`

Boots item. Assign to set equipped item.

## Attributes

All attributes are read/write. Assign to set the base value.

### `entity.speed`

**type:** `number`

Movement speed.

### `entity.armor`

**type:** `number`

Armor value.

### `entity.armorToughness`

**type:** `number`

Armor toughness.

### `entity.attackDamage`

**type:** `number`

Attack damage.

### `entity.attackSpeed`

**type:** `number`

Attack speed.

### `entity.knockbackResistance`

**type:** `number`

Knockback resistance.

### `entity.luck`

**type:** `number`

Luck attribute.

### `entity.stepHeight`

**type:** `number`

Step height.

### `entity.blockBreakSpeed`

**type:** `number`

Block break speed.

### `entity.gravity`

**type:** `number`

Gravity multiplier.

### `entity.scale`

**type:** `number`

Entity scale.

### `entity.safeFallDistance`

**type:** `number`

Safe fall distance.

### `entity.flyingSpeed`

**type:** `number`

Flying speed.

## Tags

### `entity.tags`

**type:** `table`

Scoreboard tags proxy table. Read to iterate; assign boolean values to add (`true`) or remove (`false`) tags.

```lua
for _, tag in ipairs(entity.tags) do
  print(tag)
end
entity.tags["my_tag"] = true   -- add
entity.tags["old_tag"] = false -- remove
```

## Methods

### `entity:damage(amount, source?)`

Deals damage to the entity.

- `amount` (`number`) — Damage amount
- `source` ([`Entity`](/docs/reference/entity-api), optional) — Attacking entity

```lua
entity:damage(10)
entity:damage(5, attacker)
```

### `entity:raycast(range, includeFluids?)`

Performs a raycast from the entity's eyes.

- `range` (`number`) — Max distance
- `includeFluids` (`boolean`, optional) — Include fluid blocks

Returns a hit result or `nil`.

```lua
local hit = entity:raycast(10)
if hit then
  -- hit.pos, hit.entity, hit.block
end
```

### `entity:addEffect(effectId, duration, amplifier?, particles?, icon?)`

Adds a potion effect.

| Param | Type | Default | Description |
|---|---|---|---|
| `effectId` | `number` | — | Effect type ID (1 = Speed, 5 = Strength) |
| `duration` | `number` | — | Duration in ticks |
| `amplifier` | `number` | `0` | Effect amplifier |
| `particles` | `boolean` | `true` | Show particles |
| `icon` | `boolean` | `true` | Show effect icon |

```lua
entity:addEffect(1, 600, 1, true, true)  -- Speed II, 30s
```

### `entity:removeEffect(effectId)`

Removes a potion effect.

- `effectId` (`number`) — Effect type ID

```lua
entity:removeEffect(1)
```

### `entity:hasEffect(effectId)`

Checks if an effect is active.

- `effectId` (`number`) — Effect type ID

Returns `true` or `false`.

### `entity:setOnFireFor(ticks)`

Sets the entity on fire for a duration.

- `ticks` (`number`) — Duration in ticks

```lua
entity:setOnFireFor(100)  -- 5 seconds
```

### `entity:readNbt()`

Returns the entity's NBT data as a Lua table.

### `entity:writeNbt(data)`

Writes NBT data from a Lua table.

- `data` (`table`) — NBT data

```lua
entity:writeNbt({ CustomName = '{"text":"Bob"}', Health = 40.0 })
```
