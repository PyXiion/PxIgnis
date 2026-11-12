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
- `block.place` fires once per placement and only when the held item is a block item: `pos` is where the block goes
  (not the block that was clicked) and `block` is the block that is placed. Cancelling prevents the placement.
- `player.pickup` fires once per pickup attempt, after the pickup delay has passed.
- `player.consume` is for players only. Cancelling stops the use and resends the player's inventory.
- `block.break`, `block.place` and `player.use_item` report namespaced ids (`minecraft:stone`).
- `entity.hurt` runs before the damage is applied (cancel to prevent it); `entity.damaged` after.
- `entity.death` fires on fatal damage, before a totem of undying gets a chance to save the entity. Cancelling it
  keeps the entity alive.

## Custom events: `mc.emit(event, ...)`

Scripts can fire their own events. A custom event name is namespaced, `prefix:name` (for example
`arena:boss_killed`), with at least one character on each side of the first `:`. `mc.emit` raises an error for any
other name, built-in names included.
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
`mc.emit` reaches `mc.on` handlers only. To scope a custom event to one region, use
[`region:emit`](/reference/region-api#regionemitevent-) and `region:on` on that region.

## Async handlers

Handlers run as coroutines, so `async.sleep` / `async.fetch` (`require "async"`) work directly. The event does not
wait for them: decide whether to cancel **before the first async call**. Calling `e:cancel()` after it is an error.

## Unknown names

`mc.on` and `region:on` accept the built-in events of their table and namespaced custom names. Any other name
raises an error at the call, so the script stops there instead of registering a handler that never runs. A name
close to a built-in one gets a hint:
`unknown event 'player.jion'. Did you mean 'player.join'?`

Otherwise the error reads
`unknown event 'boss_killed'. Custom events must be namespaced, e.g. 'mymod:boss_killed'`.

The names used up to 0.17 (`player_join`, `player_block_break`, ...) are unknown names too; the
[changelog](/changelog#event-names-before-018) maps each one to its replacement.

## Time limit

A handler (or any other Lua call on the server thread) that runs longer than 5 seconds is stopped with the error
`script exceeded the 5000ms time limit`. `pcall` cannot catch and continue past it. Server owners can change the
limit with the JVM flag `-Dpxignis.scriptTimeoutMs=<ms>` (`0` disables it).
