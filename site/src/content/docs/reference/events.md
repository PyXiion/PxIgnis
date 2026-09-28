---
title: Events
description: Server-side event handling with mc.on() — event tables, cancellation, priorities and the full event reference.
---

Subscribe with `mc.on(eventName, handler, opts?)`. The handler gets one argument, the **event table** `e`:

```lua
mc.on("player.join", function(e)
    mc.broadcast(e.player.name .. " joined the server!")
end)
```

`mc.on` returns a handler ID; pass it to `mc.off(id)` to unsubscribe.

## The event table

Every event table has:

| Field           | Description                                                                 |
|-----------------|-----------------------------------------------------------------------------|
| `e.name`        | The event name, e.g. `"block.break"`                                        |
| `e.cancellable` | Whether the event can be cancelled                                          |
| `e.cancelled`   | `true` once a handler cancelled it                                          |
| `e:cancel(reason?)` | Cancels the event. For `player.login` the reason is the kick message    |

plus the fields of the event, listed [below](#event-reference).

## Cancelling

```lua
mc.on("block.break", function(e)
    if not e.player:hasPermission("build") then
        e.player:sendMessage("You cannot break blocks here!")
        e:cancel()
    end
end)
```

Once an event is cancelled, **the handlers after it are not called**, so a reward script does not pay for a block
that a protection script refused. A handler that needs to see cancelled events too (logging, statistics) asks for
them with `receiveCancelled`:

```lua
mc.on("block.break", function(e)
    log(e.player.name, e.block, e.cancelled and "denied" or "ok")
end, { receiveCancelled = true, priority = "lowest" })
```

Such a handler may also un-cancel the event with `e.cancelled = false`.

`e:cancel()` on an event that is not cancellable is an error.

### Errors cancel

If a handler of a cancellable event throws an error, **the event is cancelled** and the error is reported. A broken
whitelist or protection script then blocks the action instead of silently letting everyone through. Errors in
handlers of other events are reported and the remaining handlers still run.

## Options

`mc.on(event, handler, opts)` and `region:on(event, handler, opts)` accept:

| Option             | Default | Description                                                                                      |
|--------------------|---------|--------------------------------------------------------------------------------------------------|
| `priority`         | `0`     | Higher runs first; equal priorities run in registration order. A number, or one of the aliases below |
| `receiveCancelled` | `false` | Also call this handler when the event is already cancelled                                       |
| `throttle`         | `0`     | Call this handler at most once per this many ticks                                               |

Priority aliases: `"highest"` = 200, `"high"` = 100, `"normal"` = 0, `"low"` = -100, `"lowest"` = -200. Any number
works, e.g. `{ priority = 150 }` runs between `"high"` and `"highest"`.

Typical layout: checks that may cancel use `"high"`, ordinary handlers keep the default, observers use `"lowest"` with
`receiveCancelled = true`.

Unknown option names are an error, so a typo like `{ receiveCanceled = true }` does not go unnoticed.

## Event reference

✅ = cancellable.

| Event             | Fields                                                   |    |
|-------------------|----------------------------------------------------------|:--:|
| `init`            | —                                                        |    |
| `uninit`          | —                                                        |    |
| `server_start`    | —                                                        |    |
| `server_stop`     | —                                                        |    |
| `tick`            | —                                                        |    |
| `player.login`    | `player`                                                 | ✅ |
| `player.join`     | `player`                                                 |    |
| `player.leave`    | `player`                                                 |    |
| `player.respawn`  | `player`, `alive`                                        |    |
| `player.chat`     | `player`, `message`                                      | ✅ |
| `player.move`     | `player`, `from`, `to`                                   |    |
| `player.use_item` | `player`, `hand`, `item`, `itemId`                       | ✅ |
| `player.attack`   | `player`, `target`                                       | ✅ |
| `player.interact` | `player`, `target`, `hand`                               | ✅ |
| `player.kill`     | `player`, `target`, `source`                             |    |
| `player.consume`  | `player`, `item`                                         | ✅ |
| `player.pickup`   | `player`, `item`, `count`                                | ✅ |
| `player.drop`     | `player`, `item`, `count`                                | ✅ |
| `block.break`     | `player`, `pos`, `block`                                 | ✅ |
| `block.place`     | `player`, `pos`, `block`                                 | ✅ |
| `entity.spawn`    | `entity`                                                 |    |
| `entity.despawn`  | `entity`                                                 |    |
| `entity.hurt`     | `entity`, `player?`, `source`, `amount`, `attacker`      | ✅ |
| `entity.damaged`  | `entity`, `player?`, `source`, `amount`, `attacker`, `blocked` |    |
| `entity.death`    | `entity`, `player?`, `source`, `amount`, `attacker`      | ✅ |

### Fields

- **player** — a [Player](/reference/player-api). On `entity.*` events, `e.player` is set (to the same object as
  `e.entity`) only when the entity is a player: `if e.player then ... end`.
- **entity**, **target**, **attacker** — [entities](/reference/entity-api); `attacker` is `nil` for damage without an
  attacker (fall, fire, ...).
- **source** — the damage type: the last segment of its registry ID, e.g. `"player_attack"`, `"fall"`, `"in_fire"`.
- **amount** — damage in half-hearts. On `entity.hurt` it is the incoming damage, on `entity.damaged` the damage
  actually taken.
- **blocked** — whether a shield blocked the damage.
- **pos**, **from**, **to** — [vectors](/reference/vector-api).
- **block** — block ID, e.g. `"minecraft:stone"`.
- **item** — an [ItemStack](/reference/itemstack-api) (`nil` on `player.use_item` with an empty hand); **itemId** is
  its ID string.
- **hand** — `"main"` or `"off"`.
- **alive** — on `player.respawn`, `true` when returning from the End, `false` after death.

### Notes

- `player.login` runs while the player is connecting. Cancel it to refuse the connection; `e:cancel("reason")` sets
  the kick message. For messages, sidebars and the like use `player.join`.
- `block.place` only fires when the held item is a block item.
- `entity.hurt` runs before the damage is applied (cancel to prevent it); `entity.damaged` after.
- `entity.death` fires on fatal damage, before a totem of undying gets a chance to save the entity. Cancelling it
  keeps the entity alive.

## Custom events: `mc.emit(event, ...)`

Scripts can fire their own events. The name must not be a built-in one; use a prefix such as `mymod:`.
Handlers of custom events get the emitted arguments as they are and cancel by returning `false`; `mc.emit` returns
`false` if a handler did.

```lua
-- one script
mc.on("arena:boss_killed", function(player, bossName)
    mc.broadcast(player.name .. " defeated " .. bossName .. "!")
end)

-- another script
if mc.emit("arena:boss_killed", player, "Ender Dragon") then
    giveReward(player)
end
```

All handlers run before `emit` returns. Priorities, `receiveCancelled` and `throttle` work as for built-in events.

## Async handlers

Handlers run as coroutines, so `async.sleep` / `async.fetch` (`require "async"`) work directly. The event does not
wait for them: decide whether to cancel **before the first async call**. Calling `e:cancel()` after it is an error.

## Typos

`mc.on` with an unknown event name logs a warning (also sent to operators in chat, see
[Getting Started](/guide/getting-started#script-errors-in-chat)) with the closest name:
`unknown event 'player.jion', the handler will never be called. Did you mean 'player.join'?`

## Time limit

A handler (or any other Lua call on the server thread) that runs longer than 5 seconds is stopped with the error
`script exceeded the 5000ms time limit`. `pcall` cannot catch and continue past it. Server owners can change the
limit with the JVM flag `-Dpxignis.scriptTimeoutMs=<ms>` (`0` disables it).

## Old event names

Up to 0.17 the events had other names and passed positional arguments; handlers cancelled by returning `false`.
Those names still work, so old scripts keep running, but each one logs a deprecation warning once per reload.
Switch to the new names when convenient:

| Old (positional arguments)                              | New                 |
|---------------------------------------------------------|---------------------|
| `player_join_init(player)`                              | `player.login`      |
| `player_join(player)`                                   | `player.join`       |
| `player_leave(player)`                                  | `player.leave`      |
| `player_respawn(player, alive)`                         | `player.respawn`    |
| `player_chat(player, message)`                          | `player.chat`       |
| `player_move(player, from, to)`                         | `player.move`       |
| `player_use_item(player, hand, item, itemId)`           | `player.use_item`   |
| `player_attack_entity(player, entity)`                  | `player.attack`     |
| `player_interact_entity(player, entity, hand)`          | `player.interact`   |
| `player_kill(player, target, damageSource)`             | `player.kill`       |
| `player_consume_item(player, item)`                     | `player.consume`    |
| `player_pickup_item(player, item, count)`               | `player.pickup`     |
| `player_drop_item(player, item, count)`                 | `player.drop`       |
| `player_block_break(player, pos, blockId)`              | `block.break`       |
| `player_block_place(player, pos, blockId)`              | `block.place`       |
| `entity_spawn(entity)` / `entity_despawn(entity)`       | `entity.spawn` / `entity.despawn` |
| `player_hurt(player, damageType, amount)`               | `entity.hurt` with `e.player` |
| `entity_hurt(entity, damageType, amount, source)`       | `entity.hurt`       |
| `player_damage(player, damageType, amount, blocked)`    | `entity.damaged` with `e.player` |
| `entity_damage(entity, damageType, amount, source, blocked)` | `entity.damaged` |
| `player_death(entity, damageType, amount)`              | `entity.death` with `e.player` |
| `entity_death(entity, damageType, amount)`              | `entity.death`      |

Handlers under old names follow the new rules too: they are skipped once the event is cancelled (unless
`receiveCancelled`), and an error in a cancellable event cancels it. Built-in events can no longer be fired with
`mc.emit`.
