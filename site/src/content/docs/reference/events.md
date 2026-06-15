---
title: Events
description: Server-side event handling with mc.on() — full event reference.
---

Handle server events with `mc.on(eventName, handler)`. All events are wired through Fabric API callbacks in the mod lifecycle — no mixins are used for events.

```lua
mc.on("player_join", function(ctx, player)
    mc.broadcast(player.name .. " joined the server!")
end)
```

## Event Reference

| Event | Handler Args | Cancellable |
|-------|-------------|:-----------:|
| `server_start` | `()` | ❌ |
| `server_stop` | `()` | ❌ |
| `player_join` | `(ctx, player)` | ✅ |
| `player_leave` | `(ctx, player)` | ❌ |
| `player_death` | `(ctx, player, message)` | ❌ |
| `player_chat` | `(ctx, player, message)` | ✅ |
| `player_block_break` | `(ctx, player, pos)` | ✅ |
| `player_block_place` | `(ctx, player, pos)` | ✅ |
| `player_use_item` | `(ctx, player, hand)` | ✅ |
| `player_attack_entity` | `(ctx, player, entity)` | ✅ |
| `player_interact_entity` | `(ctx, player, entity, hand)` | ✅ |
| `player_hurt` | `(ctx, player, damageType, amount)` | ✅ |
| `entity_hurt` | `(ctx, entity, damageType, amount, source)` | ✅ |
| `player_damage` | `(ctx, player, damageType, amount)` | ❌ |
| `entity_damage` | `(ctx, entity, damageType, amount, source)` | ❌ |
| `player_kill` | `(ctx, player, target)` | ❌ |

## Cancellable Events

For cancellable events, return `false` to cancel:

```lua
mc.on("player_block_break", function(ctx, player, pos)
    if not player:hasPermission("build") then
        player:sendMessage("You cannot break blocks!")
        return false  -- cancels the block break
    end
end)
```

## Notes

- **hand** is `"main"` or `"off"` — present on `player_use_item`, `player_interact_entity`, and `player_block_place`.
- **damageType** is the last segment after `.` in the damage type registry ID (e.g. `"player_attack"`, `"fall"`, `"in_fire"`, `"on_fire"`).
- **blocked** is available on `player_hurt` and `entity_hurt` — a boolean indicating whether the damage was blocked by a shield.
- **source** on `entity_hurt` / `entity_damage` is the entity that caused the damage (or `nil`).
- **pos** is a `{x, y, z}` table.
