---
title: display
description: Virtual display entities - math, spawn helpers, animation sugar, rigs, timelines and a bar widget.
---

`core:display` is the convenient layer over [`world:display`](/reference/display-api): packet-only display entities
with per-player visibility. Everything is removed from clients on `/ignis reload` and shutdown, so create your
decorations when the script loads.

```lua
local display = require "core:display"

local orb = display.item(world, pos, "minecraft:ender_pearl", { billboard = "center", scale = 0.4, glow = true })
orb:animate({ transform = { s = 0.8 } }, 10)   -- 10 ticks of smooth growth
orb:life(100)                                  -- removed after 5 seconds
```

Helpers return a **handle** that forwards `set`, `moveTo`, `attach`, `detach`, `show`, `hide`, `remove`, `onClick`,
`onAttack`, reads `valid`, `pos`, `kind`, `id`, and keeps the core handle in `h.h`. `display.wrap(core)` wraps a handle from
`world:display`.

## Math

Quaternions are `{x=, y=, z=, w=}`; matrices are row-major 16-number arrays (the `transform` matrix form).

```lua
local q, m = display.quat, display.mat
q.axis({0, 1, 0}, math.pi / 2)      -- rotation about an axis (any length)
q.euler(yaw, pitch, roll)           -- radians; roll applied first, then pitch, then yaw
q.mul(a, b)                         -- applies b first, then a
q.slerp(a, b, t); q.identity()

m.compose(t, l, s, r)               -- translate * left rotation * scale * right rotation; all optional
m.mul(a, b); m.apply(matrix, point) -- point -> {x, y, z}
m.from(transform)                   -- table or matrix form -> matrix
```

## Spawning

```lua
display.item(world, pos, "minecraft:stone", opts)    -- id or item table (with item_model)
display.block(world, pos, "minecraft:red_concrete", opts)
display.text(world, pos, "§cHi", opts)               -- billboards to the viewer by default
display.quad(world, pos, 0xFFFF0000, w, h, opts)     -- w x h block rectangle (text display with a background)
```

`opts` is any [property](/reference/display-api#properties) plus the shortcuts `scale`, `rotation`, `offset`, which
are folded into `transform` when it is not given. The helpers set explicit defaults: `interp = 0`, `teleport = 0` and
`brightness = 15` (full light; pass `brightness = false` for world light). A quad's origin is its bottom centre.

## Sugar

| Method | Description |
|---|---|
| `h:animate(props, ticks)` | `set` with `interp = ticks` |
| `h:spin(axis, period, opts?)` | Endless rotation about the model's own axis, one turn per `period` ticks, in steps shorter than 180° (`step`, `dir`, `phase`). `h:stopSpin()` stops it |
| `h:flyTo(pos, ticks)` | `moveTo` with `teleport = min(ticks, 59)` |
| `h:fade(ticks, thenRemove)` | Text turns transparent, other kinds shrink; optionally removes it afterwards |
| `h:life(ticks)` | Removes it after `ticks` |

`remove` also cancels the timers the handle started.

## Groups and rigs

```lua
local rig = display.group(world, pos, {
    body = { kind = "block", block = "minecraft:stone", transform = { s = 0.5 } },
    head = { kind = "item", item = "minecraft:player_head", parent = "body", transform = { t = {0, 1, 0} } },
})
rig:transform({ t = {0, 1, 0}, l = display.quat.euler(1, 0, 0) }, 4)  -- parent transform; children follow
rig:node("head"):set { transform = { t = {0, 2, 0} } }               -- local transform, subtree recomputed
rig:attach(entity, offset); rig:show(player); rig:remove()
```

A node's matrix is `rig * parent chain * local`, computed in Lua and sent as a matrix. `kind` is `item`, `block` or
`text`; every other key is a display property. Unknown parents and cycles are errors.

## Timelines

```lua
local anim = display.timeline {
    loop = true, step = 4,                       -- ticks per update
    tracks = { head = { { t = 0, rot = {0, 0, 0} }, { t = 20, rot = {0, 1.57, 0}, ease = "inOut" } } },
}
local play = display.play(rig, anim)             -- play:stop(), play.done
```

Keyframes have `t` (ticks) and any of `pos`, `rot` (`{rx, ry, rz}` radians), `scale`; `ease` (`linear`, `in`, `out`,
`inOut`) shapes the segment that ends at the keyframe. Tracks name rig nodes; on a single handle all tracks drive it.
Each update sets the pose one step ahead with `interp = step`.

## Bar

```lua
local bar = display.bar(world, pos, { w = 1.0, h = 0.1, fg = 0xFF33CC33, bg = 0x80000000 })
bar:set(0.75, 5)                                  -- fill over 5 ticks
bar:attach(mob, vec(0, 2.2, 0)); bar:remove()
```

Two billboarded rectangles; `fg` shrinks from the right through scale. `viewRange`, `viewers` and the other group
properties can be passed in the options. The rig methods work on the bar.
