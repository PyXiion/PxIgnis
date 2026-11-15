---
title: ref
description: Local reactive values with watchers, effects and computed values.
---

`core:ref` holds a value and notifies you when it changes.

```lua
local ref = require "core:ref"

local hp = ref.new(20)
local stop = hp:watch(function(new, old) mc.broadcast("hp " .. old .. " -> " .. new) end)

hp:set(18)                          -- watcher runs
hp:set(18)                          -- same value: nothing runs
hp:update(function(v) return v - 1 end)
stop()                              -- disconnect
```

## Refs

| Call | Description |
|---|---|
| `ref.new(v)` | Creates a ref holding `v`. |
| `r:get()` | Returns the value. Effects and computed refs record the read (see below). |
| `r:peek()` | Returns the value without recording the read. |
| `r:set(v)` | Replaces the value. |
| `r:update(fn)` | Sets the value to `fn(old)`. |
| `r:watch(fn)` | Calls `fn(new, old)` after each change. Returns a function that disconnects the watcher. |
| `ref.isRef(x)` | True if `x` is a ref. |

Watchers:

- A watcher runs only after a change, not when you register it.
- Setting a value that is `rawequal` to the current one is not a change. Setting the same table again does nothing.
- A ref can have several watchers. They run in registration order.
- Disconnecting twice is harmless. You can also disconnect from inside a notification.
- If a watcher errors, you get a script warning. The new value is already stored and the other watchers still run.

Changing a table in place is not detected. To trigger watchers, `set` or `update` the ref with a new table.

## Effects

`ref.effect(fn)` runs `fn` right away and records every ref that `fn` reads with `get()`. When one of those refs
changes, `fn` runs again. It returns `dispose`, which stops the effect.

```lua
local a, b, useA = ref.new(1), ref.new(10), ref.new(true)
ref.effect(function()
    print(useA:get() and a:get() or b:get())   -- depends on `b` only while useA is false
end)
```

- Dependencies are collected again on every run, so a branch that was not taken is not tracked.
- Effects and computed refs created inside an effect belong to that run. They are disposed before the next run.
- An effect that keeps changing its own dependencies is stopped after 100 re-runs.

## Computed refs

`ref.computed(fn)` is a read-only ref whose value is `fn()`, recomputed whenever the refs it reads change.

```lua
local price, qty = ref.new(3), ref.new(2)
local total = ref.computed(function() return price:get() * qty:get() end)
total:watch(function(new) print("total", new) end)
qty:set(5)   -- prints "total 15"
```

- `set` and `update` raise an error.
- `r:dispose()` stops recomputing.
- Reading a computed ref inside an effect makes the effect depend on it.

## Custom reactive containers

Any object with a `:watch(fn)` method that returns a disconnect function can be tracked. Call `ref.track(self)` in
its read method.
