---
title: Events
description: Server-side event handling with mc.on() — full event reference.
---

Handle server events with `mc.on(eventName, handler)`.

`mc.on` returns a handler ID (integer) that can be passed to `mc.off(id)` to unsubscribe.

```lua
local id = mc.on("player_join", function(player)
    mc.broadcast(player.name .. " joined the server!")
end)
-- later:
mc.off(id)
```

## Event Reference

| Event                    | Handler Args                                     | Cancellable |
|--------------------------|--------------------------------------------------|:-----------:|
| `server_start`           | `()`                                             |      ❌      |
| `server_stop`            | `()`                                             |      ❌      |
| `init`                   | `()`                                             |      ❌      |
| `uninit`                 | `()`                                             |      ❌      |
| `player_join_init`       | `(player)`                                       |      ✅      |
| `player_join`            | `(player)`                                       |      ❌      |
| `player_respawn`         | `(player, alive)`                                |      ❌      |
| `player_leave`           | `(player)`                                       |      ❌      |
| `player_death`           | `(player, damageType)`                           |      ❌      |
| `player_chat`            | `(player, message)`                              |      ✅      |
| `player_block_break`     | `(player, pos, blockId)`                         |      ✅      |
| `player_block_place`     | `(player, pos, blockId)`                         |      ✅      |
| `player_use_item`        | `(player, hand, item, itemId)`                   |      ✅      |
| `player_attack_entity`   | `(player, entity)`                               |      ✅      |
| `player_interact_entity` | `(player, entity, hand)`                         |      ✅      |
| `player_hurt`            | `(player, damageType, amount)`                   |      ✅      |
| `entity_hurt`            | `(entity, damageType, amount, source?)`          |      ✅      |
| `player_damage`          | `(player, damageType, amount, blocked)`          |      ❌      |
| `entity_damage`          | `(entity, damageType, amount, source?, blocked)` |      ❌      |
| `player_kill`            | `(player, target, damageSource)`                 |      ❌      |
| `entity_spawn`           | `(entity)`                                       |      ❌      |
| `entity_despawn`         | `(entity)`                                       |      ❌      |
| `entity_death`           | `(entity, damageType, amount)`                   |      ✅      |
| `player_consume_item`     | `(player, item)`                              |      ✅      |
| `player_pickup_item`      | `(player, itemStack, count)`                  |      ✅      |
| `player_drop_item`        | `(player, itemStack, count)`                  |      ✅      |
| `player_move`             | `(player, from, to)`                          |      ❌      |
| `tick`                   | `()`                                             |      ❌      |

## `mc.off(id)`

Removes a previously registered event handler by its ID. Returns `true` if the handler was found and removed, `false`
otherwise. See also `mc.emit()` below.

```lua
local id = mc.on("player_join", function(p)
    mc.broadcast(p.name .. " joined!")
end)
mc.off(id)  -- unsubscribes
```

## `mc.emit(event, ...)`

Fires a custom event from Lua, triggering all registered handlers. You can use it with `mc.on()` to create cross-script
communication.

```lua
-- In one script:
mc.on("my_mod:boss_killed", function(player, bossName)
    mc.broadcast(player.name .. " defeated " .. bossName .. "!")
end)

-- In another script:
mc.emit("my_mod:boss_killed", player, "Ender Dragon")
```

`mc.emit` can fire any event name, including built-in ones. The event is delivered synchronously - all handlers run
before `emit` returns.

While emitting built-in events is supported, I don't think you should do it.

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
- **pos** is a [Vector](/reference/vector-api).

- **from**/**to** on `player_move` are [vectors](/reference/vector-api).
- **alive** on `player_respawn` is `true` if the player respawned alive, `false` if they died and respawned.
- **player_block_place** only fires when the held item is a `BlockItem` - right-clicking with a non-block item (food,
  tool, etc.) does not trigger it.
- **Async** is **not available** in event handlers. Use `mc.schedule(0, function() ... end)` to defer async work from an
  event.
