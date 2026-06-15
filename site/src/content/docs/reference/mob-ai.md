---
title: Mob AI
description: Custom mob behaviours with mc.registerBehaviour, mob:setAI, and built-in behaviours.
---

Define and assign custom AI behaviours to mobs. Any `MobEntity` subtype returned by `world:spawn()` is a **MobWrapper** with AI methods.

Metatable name: `"mob"`

## Registering Behaviours

```lua
mc.registerBehaviour("my_behaviour", function(self, ctx)
    -- self: the mob wrapper
    -- ctx: {deltaTime, tick}
    
    if self.target then
        self:navigateTo(self.target)
    end
end)
```

## Assigning AI

```lua
-- By registered ID
mob:setAI("my_behaviour")

-- By inline function
mob:setAI(function(self, ctx)
    self:lookAt(self.target)
    self:moveToward(self.target.pos, self.speed)
end)

-- Clear AI
mob:clearAI()
```

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `mob.isMob` | boolean | Always `true` |
| `mob.target` | entity or nil | Current target entity. Read/write — assign an entity wrapper (player, mob, etc.), or `nil` to clear. Only `LivingEntity` types are valid targets; non-living entities are silently ignored. |
| `mob.speed` | number | Movement speed |
| `mob.pathRemaining` | number | Path completion progress (0–1) |
| `mob.pathFound` | boolean | Whether a valid path was found |
| `mob.aiActive` | boolean | Whether a behaviour is currently running |

## Methods

| Method | Description |
|--------|-------------|
| `mob:navigateTo(x, y, z)` | Navigate to a position |
| `mob:navigateTo(entity)` | Navigate to an entity |
| `mob:stopNavigation()` | Stop current pathfinding |
| `mob:lookAt(x, y, z)` | Look at a position |
| `mob:lookAt(entity)` | Look at an entity |
| `mob:moveToward(vec, speed)` | Move toward a vector at given speed |
| `mob:jump()` | Make the mob jump |
| `mob:canSee(entity)` | Returns `true` if the mob has line of sight |
| `mob:distanceTo(entity)` | Returns distance to entity or vector |
| `mob:distanceTo(vec)` | Returns distance to entity or vector |

```lua
register("pet", function(ctx)
    local p = ctx.player
    local spawnAt = p.pos + Vec(2, 0, 0)
    local mob = p.world:spawn("wolf", spawnAt)
    mob:setAI("pet")
end)
```

## Built-in Behaviours

| Behaviour | Description |
|-----------|-------------|
| `guard` | Follows and protects the nearest player, attacks hostile mobs |
| `pet` | Follows the owner, teleports when too far |
| `orbiter` | Circles around a fixed point |
| `statue` | Stands still, faces its spawn direction |
| `wander` | Random wandering within a radius |

```lua
mob:setAI("orbiter")
```

## Spawning Mobs

`world:spawn()` returns a MobWrapper for mob types:

```lua
local zombie = world:spawn("zombie", Vec(100, 64, 200), {
    on_spawn = function(mob)
        mob:setAI("guard")
        mob.speed = 0.3
    end
})
```
