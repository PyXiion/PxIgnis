# Event model

What `mc.on` / `region:on` do and why. The user-facing reference is `site/src/content/docs/reference/events.md`.

## Problems with the 0.17 model

- **Fail-open.** `fireWithResults` skipped a handler that threw, and callers only looked for a `false` result. A
  whitelist on `player_join_init` or a protection handler that crashed let the action through.
- **Cancellation did not stop anything.** Every handler ran and the decision was taken afterwards, so a reward
  handler paid for a block that a protection handler refused, with no way to know.
- **Order = file names.** Handlers ran in registration order, i.e. alphabetical file order.
- **Positional arguments.** Fields could only be appended; events with the same name had different signatures
  (global `player_death(entity, type, amount)` vs region `player_death(entity, source)`); damage was split into four
  overlapping events.
- **No name check.** `mc.on("player_joim", ...)` silently never fired.
- **Throttle** existed only on `region:on`, was ignored for cancellable events, and only counted down on ticks that
  had a `tick` handler.

## Design

- **One event table per event** (`e`). Fields are named, so events can grow without breaking handlers. The table is
  built only when someone listens (`EventBus.post(name) { fill }`), so hot events (`player.move`, `tick`) cost a map
  lookup when unused.
- **`e:cancel(reason?)` / `e.cancelled`.** State lives in the table, so a later handler can read it and (with
  `receiveCancelled`) reset it. The reason doubles as the kick message for `player.login`.
- **Cancelled events skip handlers by default.** The common case is "protect, then react"; a reaction to something
  that did not happen is the bug. Observers opt in with `receiveCancelled = true` (the opposite of Bukkit's
  `ignoreCancelled`, whose default is the surprising one).
- **Numeric priorities, higher first**, with aliases `lowest/low/normal/high/highest` = -200/-100/0/100/200. Numbers
  leave room between the aliases; "higher runs first" reads naturally with skip-when-cancelled: checks go high,
  ordinary handlers at 0, observers low. Ties keep registration order (stable insert).
- **Fail closed** for built-in cancellable events: a throwing handler cancels the event and the error is reported.
  Custom events (`mc.emit`) are not cancelled by errors: they are mostly notifications, and one broken listener
  should not silence the others.
- **Async handlers** return to the dispatcher at their first yield. Anything they do afterwards is too late to
  affect the event, so `e:cancel()` on a finished event throws instead of silently doing nothing.
- **Names.** Built-in events use `category.action` (`player.join`, `block.break`, `entity.hurt`); lifecycle events
  keep their old names (`init`, `tick`, ...) since they had no arguments and an extra `e` argument breaks nothing.
  Region events drop the prefix (`enter`, `leave`, `move`, `death`). Damage is two events: `entity.hurt` (before,
  cancellable) and `entity.damaged` (after); `e.player` is set when the entity is a player instead of separate
  `player_*` variants.
- **Unknown names** warn with the closest known name (Levenshtein, `_` = `.`). Names containing `:` are custom and
  never warned about.
- **Throttle** uses a clock (the bus's own tick counter, or `RegionManager.ticks` for regions) instead of
  per-handler countdowns, so it works whatever else is registered.

## Compatibility

Old names are `LegacyEvent` views over the new events: a filter (e.g. `player_hurt` = `entity.hurt` where
`e.player ~= nil`) and a function that rebuilds the old positional arguments from `e`. Their handlers sit in the
same priority-ordered list as new ones and `return false` still cancels. They log a deprecation warning once per
name per reload.

Deliberate breaks, all towards the safer behaviour: old handlers are also skipped after a cancel and also fail
closed; `mc.emit` refuses built-in names (it would have delivered positional args to handlers expecting `e`).

## Not done

- Mutable fields (e.g. changing `e.amount` in `entity.hurt`, `e.message` in `player.chat`) would be the next step:
  read the field back after dispatch.
- Each handler call still creates a `LuaThread`. Reusing threads for handlers that finish without yielding would cut
  allocation on hot events.
