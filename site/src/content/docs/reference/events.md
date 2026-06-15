---
title: Events
description: Server-side event handling with mc.on() — full event reference.
---

Handle server events with `mc.on(eventName, handler)`. All events are wired through Fabric API callbacks in the mod
lifecycle — no mixins are used for events.

Event handlers do **not** receive a `ctx` argument — unlike command handlers, they receive the event-specific arguments
directly.

```lua
mc.on("player_join", function(player)
    mc.broadcast(player.name .. " joined the server!")
end)
```

## Event Reference

| Event                    | Handler Args                                    | Cancellable |
|--------------------------|-------------------------------------------------|:-----------:|
| `server_start`           | `()`                                            |      ❌      |
| `server_stop`            | `()`                                            |      ❌      |
| `init`                   | `()`                                            |      ❌      |
| `uninit`                 | `()`                                            |      ❌      |
| `player_join_init`       | `(player)`                                      |      ✅      |
| `player_join`            | `(player)`                                      |      ❌      |
| `player_respawn`         | `(player, alive)`                               |      ❌      |
| `player_leave`           | `(player)`                                      |      ❌      |
| `player_death`           | `(player, damageType)`                          |      ❌      |
| `player_chat`            | `(player, message)`                             |      ✅      |
| `player_block_break`     | `(player, pos, blockId)`                        |      ✅      |
| `player_block_place`     | `(player, pos, blockId)`                        |      ✅      |
| `player_use_item`        | `(player, hand, item, itemId)`                  |      ✅      |
| `player_attack_entity`   | `(player, entity)`                              |      ✅      |
| `player_interact_entity` | `(player, entity, hand)`                        |      ✅      |
| `player_hurt`            | `(player, damageType, amount)`                  |      ✅      |
| `entity_hurt`            | `(entity, damageType, amount, source)`          |      ✅      |
| `player_damage`          | `(player, damageType, amount, blocked)`         |      ❌      |
| `entity_damage`          | `(entity, damageType, amount, source, blocked)` |      ❌      |
| `player_kill`            | `(player, target, damageSource)`                |      ❌      |
| `entity_spawn`           | `(entity)`                                      |      ❌      |
| `entity_despawn`         | `(entity)`                                      |      ❌      |
| `entity_death`           | `(entity, damageType, amount)`                  |      ✅      |

## Cancellable Events

For cancellable events, return `false` to cancel:

```lua
mc.on("player_block_break", function(player, pos, blockId)
    if not player:hasPermission("build") then
        player:sendMessage("You cannot break blocks!")
        return false  -- cancels the block break
    end
end)
```

## Notes

- **damageType** is the last segment after `.` in the damage type registry ID (e.g. `"player_attack"`, `"fall"`,
  `"in_fire"`, `"on_fire"`).
- **blocked** is a boolean indicating whether the damage was blocked by a shield. Available on `player_damage` and
  `entity_damage` only (not on the cancellable hurt events).
- **source** on `entity_hurt` / `entity_damage` is the entity that caused the damage (or `nil`).
- **pos** is a `{x, y, z}` table.
- **alive** on `player_respawn` is `true` if the player respawned alive, `false` if they died and respawned.
- **player_block_place** only fires when the held item is a `BlockItem` — right-clicking with a non-block item (food,
  tool, etc.) does not trigger it.
- **Async** (`mc.sleep`, `mc.fetch`) is **not available** in event handlers — they run on the main thread, not inside a
  coroutine. Use `mc.schedule(0, function() ... end)` to defer async work from an event.
