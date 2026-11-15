---
title: ref
description: Local reactive values with watchers, effects and computed values.
---

`core:ref` holds a value and tells you when it changes.

```lua
local ref = require "core:ref"

local hp = ref.new(20)
local stop = hp:watch(function(new, old) mc.broadcast("hp " .. old .. " -> " .. new) end)

hp:set(18)                          -- watcher runs
hp:set(18)                          -- unchanged: nothing runs
hp:update(function(v) return v - 1 end)
stop()                              -- disconnect
```

## Refs

| Call | |
|---|---|
| `ref.new(v)` | a new ref |
| `r:get()` | the value (tracked, see below) |
| `r:peek()` | the value, never tracked |
| `r:set(v)` | replaces the whole value |
| `r:update(fn)` | sets `fn(old)` |
| `r:watch(fn)` | calls `fn(new, old)` after each change; returns a disconnect function |
| `ref.isRef(x)` | true for refs |

- Watchers run only after a change, never when registered. A `set` of a value `rawequal` to the current one is not a
  change (setting the same table again does nothing).
- Several watchers may be attached; they run in registration order. Disconnecting is idempotent and also works from
  inside a notification.
- A failing watcher is reported as a script warning; the value is already stored and the other watchers still run.
- Mutating a table held by a ref in place is not observed. Use `set` or `update` with a new value.

## Effects and computed values

`ref.effect(fn)` runs `fn` immediately and records every ref read with `get()` during that run. When one of them
changes, `fn` runs again; the dependencies are collected afresh each run, so a branch not taken stops being tracked.
It returns `dispose`. Effects (and computed refs) created inside an effect belong to that run and are disposed before
the next one. An effect that keeps changing its own dependencies is stopped after 100 re-runs.

```lua
local a, b, useA = ref.new(1), ref.new(10), ref.new(true)
ref.effect(function()
    print(useA:get() and a:get() or b:get())   -- depends on `b` only while useA is false
end)
```

`ref.computed(fn)` is a read-only ref holding `fn()`, recomputed automatically. `set` and `update` raise an error;
`r:dispose()` stops it recomputing. Reading it inside an effect makes that effect depend on it.

```lua
local price, qty = ref.new(3), ref.new(2)
local total = ref.computed(function() return price:get() * qty:get() end)
total:watch(function(new) print("total", new) end)
qty:set(5)   -- prints "total 15"
```

## Custom reactive containers

Anything with `:watch(fn)` (returning a disconnect function) can be tracked: call `ref.track(self)` from its read
method.
