---
title: Entity
description: Lua wrapper for Minecraft entities — properties, attributes, equipment, effects, and raycasting.
---

The entity wrapper provides access to any Minecraft entity. Player entities extend this
wrapper — see [Player API](/reference/player-api).

Metatable name: `"entity"`

## Properties

All properties are read/write unless noted as read-only.

### `entity.uuid`

**type:** `string` (read-only)

### `entity.type`

**type:** `string` (read-only)

Entity type (e.g. `"minecraft:zombie"`).

### `entity.name`

**type:** `string` (read-only)

### `entity.displayName`

**type:** `string` (read-only)

### `entity.customName`

**type:** `string` or `nil`

### `entity.world`

**type:** [`World`](/reference/world-api) (read-only)

### `entity.pos`

**type:** [Vector](/reference/vector-api)

### `entity.dir`

**type:** [Vector](/reference/vector-api)

The direction of the head.

### `entity.bodyDir`

**type:** [Vector](/reference/vector-api)

### `entity.onGround`

**type:** `boolean` (read-only) — whether the entity stands on the ground.

### `entity.isSneaking`

**type:** `boolean`

### `entity.isSprinting`

**type:** `boolean`

### `entity.fallDistance`

**type:** `number`

Idk what it means, but it exists.

### `entity.removed`

**type:** `boolean` (read-only)

### `entity.health`

**type:** `number`

### `entity.maxHealth`

**type:** `number`

### `entity.air`

**type:** `number`

### `entity.maxAir`

**type:** `number` (read-only)

### `entity.fireTicks`

**type:** `number`

`-1` means not on fire.

### `entity.glowing`

**type:** `boolean`

### `entity.invulnerable`

**type:** `boolean`

### `entity.yaw`

**type:** `number` (degrees)

Horizontal rotation; `0` faces south (+Z), `90` faces west. For living entities it also turns the body and head.
Set it before `entity.pos` when you teleport an entity each tick: the teleport carries the rotation.

### `entity.pitch`

**type:** `number` (degrees)

Vertical rotation; positive looks down.

### `entity.noGravity`

**type:** `boolean`

### `entity.velocity`

**type:** [`Vec`](/reference/vector-api)

Motion in blocks per tick (gravity adds about `-0.08` per tick on top). Assigning it also sends the new motion to
clients, so players are pushed too. Set it every tick to steer an entity.

### `entity:remove()`

Removes the entity without death, drops or loot. Players cannot be removed (raises an error).

## Equipment

### `entity.mainhand`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

### `entity.offhand`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

### `entity.head`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

### `entity.chest`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

### `entity.legs`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

### `entity.feet`

**type:** [`ItemStack`](/reference/itemstack-api) or `nil`

## Attributes

All attributes are read/write (base value).

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

Scoreboard tags. Read to iterate; assign boolean values to add (`true`) or remove (`false`) tags.

```lua
for _, tag in ipairs(entity.tags) do
  print(tag)
end
entity.tags["my_tag"] = true   -- add
entity.tags["old_tag"] = false -- remove
```

## Methods

### `entity:swing(hand?)`

Plays the arm swing (`"main"`, the default, or `"off"`). Trackers see it, and so does the entity itself when it is a
player. Living entities only; anything else raises an error.

### `entity:damage(amount, source?)`

Deals damage to the entity.

- `amount` (`number`) — Damage amount
- `source` ([`Entity`](/reference/entity-api), optional) — Attacking entity

```lua
entity:damage(10)
entity:damage(5, attacker)
```

### `entity:raycast(range, includeFluids?, includeEntities?)`

Performs a raycast from the entity's eyes.

- `range` (`number`) — Max distance
- `includeFluids` (`boolean`, optional) — Include fluid blocks
- `includeEntities` (`boolean`, optional, default `true`) — Include entity hits

Returns a hit result or `nil`.

```lua
local hit = entity:raycast(10)
if hit then
  -- hit.pos, hit.entity, hit.block
end
```

### `entity:addEffect(effectId, duration, amplifier?, particles?, icon?)`

Adds a potion effect.

| Param       | Type      | Default | Description                               |
|-------------|-----------|---------|-------------------------------------------|
| `effectId`  | `string`  | —       | Effect type ID (e.g. `"minecraft:speed"`) |
| `duration`  | `number`  | —       | Duration in ticks                         |
| `amplifier` | `number`  | `0`     | Effect amplifier                          |
| `particles` | `boolean` | `true`  | Show particles                            |
| `icon`      | `boolean` | `true`  | Show effect icon                          |

```lua
entity:addEffect("minecraft:speed", 600, 1, true, true)  -- Speed II, 30s
```

### `entity:removeEffect(effectId)`

Removes a potion effect.

- `effectId` (`string`) — Effect type ID (e.g. `"minecraft:speed"`)

```lua
entity:removeEffect("minecraft:speed")
```

### `entity:hasEffect(effectId)`

Checks if an effect is active.

- `effectId` (`string`) — Effect type ID (e.g. `"minecraft:speed"`)

Returns `true` or `false`.

### `entity:setOnFireFor(ticks)`

Sets the entity on fire for a duration.

- `ticks` (`number`) — Duration in ticks

```lua
entity:setOnFireFor(100)  -- 5 seconds
```
