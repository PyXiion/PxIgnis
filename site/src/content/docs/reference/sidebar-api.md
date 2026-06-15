---
title: Sidebar
description: Per-player scoreboard sidebars with packet-based rendering.
---

Access and control a per-player sidebar through the `player.sidebar` property. Sidebars use a **local `Scoreboard()` instance** and direct packets — no other player sees them.

Metatable name: `"sidebar"`

## Usage

### `player.sidebar = {...}`

Creates or updates the sidebar. Auto-shows on first creation.

```lua
player.sidebar = {
  title = "My Server",
  lines = { "Welcome!", "", "Online: 42" }
}
```

Partial updates merge with existing state:

```lua
player.sidebar = { title = "New Title" }
player.sidebar = { lines = { "Line 1", "Line 2" } }
player.sidebar = { visible = false }
```

### `player.sidebar = nil`

Destroys the sidebar.

```lua
player.sidebar = nil
```

## Sidebar Object

Reading `player.sidebar` returns a sidebar object:

### `sb.title`

**type:** `string`

Current title. Assign to update (sends packet if visible).

### `sb.lines`

**type:** `table`

Lines array (strings). Assign to replace all lines.

### `sb.visible`

**type:** `boolean`

Whether the sidebar is shown. Read-only.

### `sb.lineCount`

**type:** `number`

Number of lines. Read-only.

### `sb:setLine(n, text)`

Sets or updates a specific line (1‑indexed).

- `n` (`number`) — Line index
- `text` (`string`) — Line text

### `sb:show()`

Shows the sidebar.

### `sb:hide()`

Hides the sidebar.

### `sb:destroy()`

Destroys the sidebar permanently.

```lua
local sb = player.sidebar
sb.title = "Updated Title"
sb.lines = { "Line A", "Line B", "Line C" }
sb:setLine(2, "Modified Line B")
sb:hide()
-- later
sb:show()
sb:destroy()
```

## Lifetime

Sidebars are auto-destroyed on player disconnect and `/ignis reload`.
