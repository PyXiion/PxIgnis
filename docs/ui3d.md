# ui3d: in-world 3D UI panels

`core:ui3d` builds flat UI panels in the world from virtual displays: buttons, labels, bars and hover/press
animations, with values bound to `core:ref`.

## Building blocks

- Visuals: virtual displays from `world:display` (text quads for rectangles, text for labels, item displays for icons).
  Packet-only, per-viewer, batched once per tick.
- Input: hybrid.
  - **Hover**: every tick, for each viewer, the ray `entity.eyePos` + `entity.dir` is intersected with the panel plane,
    then mapped to panel-local coordinates and the widget under it. Only if the plane is hit, a block raycast
    (`world:raycast`) from the eye to the hit point checks occlusion: no hover through walls.
  - **Click**: one interaction display ("shield") covers the panel. `onClick`/`onAttack` on it give the player; the
    same ray math picks the widget. The shield is axis-aligned, so per-button interaction boxes would misalign on
    rotated panels; the ray resolves the button exactly at any orientation.
- `entity.eyePos` (core, read-only): `Entity.getEyePosition()`; correct for any entity type and pose.

## Units

1 unit = 1 block. Sizes, gaps and paddings are in blocks (e.g. `gap = 0.05`).

## DSL

Declarative tree with a small layout engine. Positional first element is the content shorthand.

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

- Containers: `col`, `row`, `stack` (layers on top of each other). Props: `gap`, `pad`, `align`, fixed or `grow` size.
- Widgets (stage 1): `text`, `button`, `icon` (item), `bar`, `toggle`.
- Structure changes: `ui.show(cond, node)`, `ui.each(listRef, fn)`; only that subtree is rebuilt.
- Per-player state: `ui.panel{...}(function(player) return tree end)` builds one tree per viewer.

## Reactivity

Every widget prop accepts a plain value, a `Ref`, or a function.

- `Ref`: the widget watches it and updates only that prop (`handle:set{...}`). No tree diffing.
- Function: computed. `core:ref` tracks which refs were read via `get()` while it ran and re-runs it when any changes
  (dependency set refreshed on each run). The tracking hook lives in `core:ref` so `net.shared` (#13) works the same.
- Every watch returns a disconnect; `p:remove()` calls all of them.

## Styles and animation

- `theme` table on the panel; named styles (`style = "danger"`).
- A style has a base and `hover`, `press`, `disabled` overrides (`bg`, `fg`, `scale`, `z`).
- Transforms (scale, z lift) animate client-side via `interp`.
- `hover` and `press` panel options (overridable per widget): `"viewer"` (default) shows the state only to the
  player causing it, through a per-player highlight display; `"shared"` shows it to every viewer.
- Hover/press colour changes interpolate together with the transform (one `interp` per display per tick).

## Stage 2

- mapcanvas pixel layer on the panel plane (input still via shield + ray).
- Panels attached to entities / following the player's view.
