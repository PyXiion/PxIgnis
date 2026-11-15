# ui3d: in-world 3D UI panels

`core:ui3d` builds flat UI panels in the world: buttons, labels, bars, icons and toggles, with hover and press
animations. Widget values can be bound to `core:ref`.

## How it works

### Visuals

Panels are drawn with virtual displays from `world:display`: text quads for rectangles, text displays for labels,
item displays for 3D icons. The displays exist only as packets, are sent per viewer, and are batched once per tick.

### Input

Input combines two mechanisms.

**Hover** is computed every tick for each viewer:

1. Cast the ray `entity.eyePos` + `entity.dir` against the panel plane.
2. Convert the hit point to panel-local coordinates and find the widget under it.
3. Only if the plane was hit, run a block raycast (`world:raycast`) from the eye to the hit point. A block in between
   means no hover, so players cannot hover through walls.

**Click** goes through one interaction display (the "shield") that covers the whole panel. Its `onClick` and
`onAttack` events tell us which player clicked; the same ray math then picks the widget.

Interaction boxes are axis-aligned, so per-button boxes would not line up with buttons on a rotated panel. One shield
covering the panel works at any orientation: the ray picks the widget.

`entity.eyePos` (core, read-only) is `Entity.getEyePosition()`. It is correct for any entity type and pose.

## Units

1 unit = 1 block. Sizes, gaps and paddings are in blocks (for example `gap = 0.05`).

## DSL

A panel is a declarative tree with a small layout engine. The first positional element of a node is its content.

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
p:get("id")          -- widget with that `id`
p:remove()           -- removes the displays and disconnects every binding
```

### Containers

`col`, `row`, and `stack` (children layered on top of each other). Props: `gap`, `pad`, `align`, and either a fixed
size or `grow`.

### Widgets

`text`, `button`, `icon`, `bar`, `toggle`.

### Icons

Icons are glyphs inside a text component, not item displays. Pick the source with one of:

```lua
ui.icon { item = "ns:path" }                 -- item sprite
ui.icon { sprite = ..., atlas = ... }        -- atlas sprite
ui.icon { glyph = ..., font = ... }          -- font glyph
ui.icon { head = player }                    -- player head (or a profile)
```

- An item id `ns:path` maps to the sprite `ns:item/path`. Under the hood this is one `object` or font component in a
  text display (`{type = "object", atlas, sprite}`).
- `model = true` renders a real 3D item display instead.
- `theme.icons` gives icons names: `ui.icon "coin"`.
- Icons can sit inline in text: `ui.text { "Price: ", ui.icon "coin", " 10" }` is a single component.
- A glyph is `display.ICON` in size: 8 x 8 px, with an 8 px advance (from `AtlasGlyphProvider`). The width of lines
  that mix text and icons is an estimate.

### Changing structure

- `ui.show(cond, node)` shows the node while `cond` is true.
- `ui.each(listRef, fn)` builds one node per list item.

Only the affected subtree is rebuilt.

### Per-player trees

`ui.panel{...}(function(player) return tree end)` builds a separate tree for each viewer.

## Reactivity

Every widget prop accepts a plain value, a `Ref`, or a function.

- **`Ref`**: the widget watches it and updates just that prop (`handle:set{...}`). The tree is never diffed.
- **Function**: a computed value. `core:ref` records which refs the function read with `get()` and re-runs it when any
  of them changes. The recorded set is refreshed on every run. The tracking lives in `core:ref`, so `net.shared` (#13)
  works the same way.
- Every watch returns a disconnect function. `p:remove()` calls all of them.

`ui.text` and `ui.button` also accept a format string with arguments: `ui.text("Score: %s", score)`. The text is
`string.format` of the string and the arguments; each argument is a plain value, a `Ref` or a function. The widget
wraps this in one computed value, so it follows the same rules as a function prop.

## Styles and animation

### Styles

- The panel has a `theme` table of named styles; a widget picks one with `style = "danger"`.
- A style has base values plus `hover`, `press` and `disabled` overrides. Overridable fields: `bg`, `fg`, `scale`, `z`.

### Animation

Scale and z lift animate on the client through `interp`. Colour changes from hover and press interpolate in the same
step, so each display gets one `interp` per tick.

### Hover and press visibility

The panel options `hover` and `press` (each overridable per widget) take:

- `"viewer"` (default): only the player causing the state sees it, through a per-player highlight display.
- `"shared"`: every viewer sees it.

### Sounds

A style may set `sound = { hover, press, disabled, volume, pitch }`. Each sound is a sound id or `false`.

- Defaults: a click sound on press; hover and disabled are silent.
- A disabled widget plays only `disabled`.
- In `"viewer"` mode the sound plays to that player alone (`Player:playSound`). In `"shared"` mode it plays at the
  widget for nearby players (`World:playSound`).

## Stage 2

- A mapcanvas pixel layer on the panel plane. Input still uses the shield and the ray.
- Panels attached to entities, or following the player's view.
