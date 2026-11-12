---
title: particles
description: Tick-driven particle effects - shapes, sequences and looping, played at a position or following an entity.
---

The `particles` library plays particle effects built from small pieces: shapes (circle, sphere, burst, line,
helix) and combinators (`seq`, `par`, `wait`, `times`, `loop`, `with`). An effect is plain data; `fx.play` runs it
one step per server tick and returns a handle you can stop or wait on.

## Loading

```lua
local fx = require "core:particles"
```

Particles are built once with the global [`particle(id, data?)`](/reference/world-api#particleid-data) and passed to shapes.

```lua
local spark = particle("end_rod")

local h = fx.play(fx.seq {
    fx.burst { particle = spark, radius = 3 },
    fx.wait(10),
    fx.times(3, fx.circle { particle = spark, radius = 2 }),
}, { at = player })
```

## `fx.play(node, opts) -> handle`

The first step runs on the next server tick.

| Option    | Type                 | Description                                                                               |
|-----------|----------------------|-------------------------------------------------------------------------------------------|
| `at`      | `Vec` or Entity/Player | Required. Where the effect is centred                                                     |
| `world`   | `World`              | Required with a `Vec` `at`; defaults to the entity's world                                |
| `follow`  | `boolean`            | Track the entity every tick and stop silently when it is removed or disconnects. Entity only |
| `viewers` | `Player` or `Player[]` | Who sees the particles (default: everyone in range)                                     |

Without `follow`, the origin and facing are snapshotted when `play` is called. With an entity, `ctx.forward` is its
horizontal look direction; with a `Vec` it is `+Z`.

| Handle           | Description                                                                              |
|------------------|------------------------------------------------------------------------------------------|
| `handle.done`    | `true` once finished or stopped                                                          |
| `handle:stop()`  | Ends the effect; returns `false` if it had already finished                              |
| `handle:wait()`  | Suspends the calling coroutine until the effect finishes or is stopped (as `async` waits) |

An error inside a step or factory stops the effect and is logged by the scheduler. Script reload drops running effects.

To chain effects, use coroutines:

```lua
async.task(function()
    fx.play(a, opts):wait()
    fx.play(b, opts):wait()
end)
```

## Nodes

Every node takes at least one tick, so `loop` cannot spin without time advancing. Invalid arguments raise errors
that start with `fx.<name>:`.

| Constructor            | Description                                                                               |
|------------------------|-------------------------------------------------------------------------------------------|
| `fx.seq(list)`         | Children one after another; the next child starts on the tick after the previous ends     |
| `fx.par(list)`         | Children together; ends with the longest                                                  |
| `fx.wait(ticks)`       | Does nothing for `ticks` >= 1                                                             |
| `fx.times(n, node)`    | `node` `n` >= 1 times, fresh each time                                                    |
| `fx.loop(node)`        | `node` forever                                                                            |
| `fx.with(opts, node)`  | `opts.offset` (`Vec`) shifts the origin; `opts.viewers` overrides the viewers for `node`  |
| `fx.effect(factory)`   | Custom effect: `factory()` returns a `step(ctx)` run once per tick; return `true` to finish. See below |
| `fx.ticking(fn, ticks?)` | `fx.effect` with a tick counter: `fn(ctx, k)` per tick, `k` from 0; ends after `ticks`, or never |

## Shapes

Common options: `particle` (required), `duration` in ticks, `emit` (a table passed on as the
[`world:emit`](/reference/world-api#worldemitparticle-pos-opts) options). All positions are relative to the origin.

| Constructor | Options (defaults)                                                                       | Draws each tick                                            |
|-------------|------------------------------------------------------------------------------------------|------------------------------------------------------------|
| `fx.circle` | `radius` (1), `points` (24), `spin` (0 rad/tick), `duration` (1)                          | A horizontal ring                                          |
| `fx.sphere` | `radius` (1), `points` (48), `spin` (0), `duration` (1)                                  | A Fibonacci sphere                                         |
| `fx.burst`  | `radius` (3), `points` (60), `duration` (12)                                              | A sphere that grows from 0 to `radius` over `duration`     |
| `fx.line`   | `to` (Vec or Entity, required), `step` (0.25), `duration` (1)                            | Points from the origin to `to`; an entity is re-read per tick |
| `fx.helix`  | `radius` (1), `height` (2), `strands` (2), `points` (24, >= 2), `turns` (2), `spin` (0.25), `duration` (40) | Rotating strands rising from the origin |

## Custom effects

`fx.effect(factory)` is the building block for your own effects; the shapes above are built on the same idea.
`factory()` runs once each time the effect starts (at `play`, and at every `times`/`loop` restart) and returns
a `step(ctx)` function that runs once per tick. Keep state in the factory's local variables, so each run starts fresh.
A step ends the effect by returning `true`; if it never does, the effect runs until stopped. Neither function may yield.

`ctx` has `world`, `origin`, `forward`, `right` (horizontal unit vectors) and `viewers`, plus `ctx:emit(particle, pos,
opts?)` and `ctx:emitMany(particle, positions, opts?)`, which work like the `world` methods and apply the
viewers set by `play`/`with` unless `opts.viewers` is given.

Most custom effects just need a tick counter. `fx.ticking(fn, ticks?)` is `fx.effect` with that counter built in:
`fn(ctx, k)` runs once per tick with `k = 0, 1, 2, ...` for the current run, and the effect ends after `ticks` ticks
(or runs until stopped when `ticks` is omitted).

```lua
local function ring(particle, radius)
    return fx.ticking(function(ctx, k)
        local a = k * 0.2
        ctx:emit(particle, ctx.origin + vec(math.cos(a) * radius, 1, math.sin(a) * radius))
    end, 200)
end
```

Use `fx.effect` itself when a run needs more state than a counter (a trail of previous positions, a random seed):

```lua
-- a reusable shape: just a function that returns an effect
local function orbit(particle, radius)
    return fx.effect(function()
        local k = 0                       -- this run's tick counter
        return function(ctx)
            local a = k * 0.2
            ctx:emit(particle, ctx.origin + vec(math.cos(a) * radius, 1, math.sin(a) * radius))
            k = k + 1
            return k >= 200               -- omit to run until stopped
        end
    end)
end

local h = fx.play(orbit(spark, 1.5), { at = player, follow = true })
```

State kept outside the factory survives restarts, which is how a `loop` keeps its phase. Prefer short-lived particles
(`dust`, `electric_spark`, `crit`) for effects that follow a moving player, and avoid redrawing a whole shape every
tick with lingering particles: they pile up into a solid cloud.
