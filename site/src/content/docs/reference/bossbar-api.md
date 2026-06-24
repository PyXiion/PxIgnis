---
title: BossBar
description: Global boss bars created with mc.createBossBar().
---

Create boss bars visible to multiple players. Boss bars persist until destroyed or until `/ignis reload`.

Metatable name: `"bossbar"`

## Creation

### `mc.createBossBar(title, color?, style?)`

| Param | Type | Default | Description |
|---|---|---|---|
| `title` | `string` | | Boss bar display title |
| `color` | `string` | `"white"` | `"pink"`, `"blue"`, `"red"`, `"green"`, `"yellow"`, `"purple"`, `"white"` |
| `style` | `string` | `"progress"` | `"progress"`, `"notched_6"`, `"notched_10"`, `"notched_12"`, `"notched_20"` |

```lua
local bar = mc.createBossBar("&cDragon Fight", "red", "notched_6")
```

## Properties

### `bar.progress`

**type:** `number`

Boss bar progress (0.0 – 1.0). Read/write.

```lua
bar.progress = 0.75
```

### `bar.visible`

**type:** `boolean`

Whether the bar is visible to its viewers. Read/write.

```lua
bar.visible = false
```

## Methods

### `bar:addPlayer(player)`

Adds a player to see this boss bar.

```lua
bar:addPlayer(player)
```

### `bar:removePlayer(player)`

Removes a player from this boss bar.

```lua
bar:removePlayer(player)
```

### `bar:destroy()`

Destroys the boss bar permanently.

```lua
bar:destroy()
```

## Lifetime

Boss bars are destroyed on `/ignis reload`. Add players after creation or in the `init` event.
