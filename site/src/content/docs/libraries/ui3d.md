---
title: ui3d
description: In-world UI panels from virtual displays - layout, buttons, bars, hover and click, bound to refs.
---

`core:ui3d` builds flat UI panels in the world from [`core:display`](/libraries/display) displays. Values bind to
[`core:ref`](/libraries/ref). 1 unit = 1 block: sizes, gaps and paddings are in blocks.

```lua
local ref = require "core:ref"
local ui  = require "core:ui3d"

local score = ref.new(0)
local hp    = ref.new(1.0)

local p = ui.panel { at = pos, facing = "north", width = 3 } {
  ui.col { gap = 0.05, pad = 0.1,
    ui.text(function() return "Score: " .. score:get() end),
    ui.bar { value = hp },
    ui.row { gap = 0.05,
      ui.button { "+1", onClick = function(player) score:update(function(n) return n + 1 end) end },
      ui.button { "Exit", style = "danger", onClick = leave,
                  disabled = function() return score:get() < 10 end },
    },
  },
}
p:get("id")          -- widget by `id`
p:remove()           -- removes displays and disconnects every binding
```

## Panel

`ui.panel(opts)(tree)`:

| Option | Meaning |
|---|---|
| `at` | Panel centre. |
| `facing` | Direction the front faces: `"south"` (default), `"north"`, `"east"`, `"west"`. |
| `width`, `height` | Size in blocks; unset means the natural size of the tree. Give `height` to keep the panel from shifting when the content changes. |
| `world` | Default: the overworld. |
| `theme` | Styles, see below. |
| `reach` | Hover and click distance, default 8. |
| `range` | Per-player panels: distance within which a player gets a tree, default 24. |

`tree` is a node, a list of nodes (layers, like `stack`), or `function(player)` returning a node: one tree per player
within `range`, shown only to that player and removed when the player leaves. A plain panel is shown to everyone near
it and has one hover and press state, shared by all viewers.

`p:get(id, player?)` returns the widget with that `id` (the `player` selects the tree of a per-player panel).
`widget:set { text = "x", disabled = true }` sets plain props. `p:remove()` removes all displays and disconnects every
binding.

## Layout

- `col`, `row`: children one after another. `stack`: children on top of each other, later ones above.
- Container props: `gap`, `pad`, `align`: placement on the cross axis, `"stretch"` (default), `"start"`, `"center"` or
  `"end"`.
- Every node: `w`, `h` (fixed size), `grow` (`true` or a weight): share of the free space along the parent's main axis.
- `ui.box { w =, h =, grow = }` is an empty spacer.

## Widgets

| Widget | Props |
|---|---|
| `ui.text("str")` / `ui.text { "str", size =, fg =, align = }` | `size` is the line height in blocks (default 0.2); `align`: `"left"`, `"center"` (default), `"right"` |
| `ui.button { "label", onClick = fn(player), disabled = }` | also `size`, `fg`, `bg`, `style` |
| `ui.icon("minecraft:apple")` | `item`, `size` |
| `ui.bar { value = 0..1 }` | `bg`, `fg` |
| `ui.toggle { value = ref, onChange = fn(new, player) }` | `disabled`; a `Ref` value is written on click |

## Reactivity

Every widget prop accepts a plain value, a `Ref` or a function. A `Ref` updates that prop when it changes. A function
is a computed value: refs read inside it with `get()` are tracked and it re-runs when one changes.

- `ui.show(cond, node)`: the node exists while `cond` is truthy.
- `ui.each(list, fn)`: one `fn(item, index)` node per entry; the group rebuilds when `list` changes.

Only the changed subtree is rebuilt; the layout is recomputed and moved widgets are updated.

## Styles

A style has `bg`, `fg`, `scale`, `z` and overrides `hover`, `press`, `disabled` with the same fields. A widget uses the
style named by `style`, else the one named after its kind (`text`, `button`, `bar`, `toggle`, `icon`). Built-in styles:
those kinds and `danger`. `theme` merges field by field over the built-in ones and may add new names.

```lua
ui.panel { at = pos, theme = {
  button = { bg = 0xFF224488, hover = { bg = 0xFF3366BB, scale = 1.1 } },
  gold   = { bg = 0xFFCC9900, fg = 0xFF000000 },
} } { ui.button { "Buy", style = "gold" } }
```

Scale and z lift animate on the client through `interp`.

## Input

- Hover: each tick, for every viewer, the ray `eyePos` + `dir` is intersected with the panel plane and mapped to the
  widget under it. A block between the eye and the hit point cancels the hover.
- Click: a transparent interaction display covers the panel; `onClick` and `onAttack` (main hand) resolve the widget
  with the same ray. A click that misses every widget does nothing; disabled widgets ignore clicks.

## Geometry helpers

`ui.layout(node, w?, h?)`, `ui.frame(at, facing, w, h)`, `ui.project(frame, eye, dir, range?)` (layout coordinates
`x, y` from the top-left corner, y down, and the ray distance, or `nil`) and `ui.hit(node, x, y)` are the pure functions
behind the panel.
