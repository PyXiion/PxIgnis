---
title: Storage
description: Persistent JSON data with mc.data, ctx.player.data.
---

PxIgnis provides persistent JSON key-value storage for global and per-player data. Data is saved automatically on server stop, player disconnect, and `/ignis reload`.

## Storage Locations

Right now it uses JSON as the storage backend. In the future it may support real databases (SQLite/Posgtres/MySQL)
without the API changes. And maybe we'll have an `sqlite` lib.

| Scope | File |
|-------|------|
| Global | `config/ignis/storage/global.json` |
| Per-player | `config/ignis/storage/players/<uuid>.json` |

## API

```lua
-- Global data (available everywhere)
mc.data.key = "value"
local v = mc.data.key

-- Per-player data (in command/event handlers)
ctx.player.data.coins = 100
local coins = ctx.player.data.coins
```

Supports numbers, strings, booleans, tables, and `nil` (deletes the key).

## Nested Tables

Deep writes work directly — no special workaround needed:

```lua
-- This persists on save:
data.stats.kills = 5
data.stats.name = "player_stats"
```

## Assigning a Table Stores a Copy

Assigning a table to `mc.data` or `player.data` stores a copy of it. Changes to the original table after the
assignment are not saved:

```lua
-- Wrong: `homes` is the local table, the saved copy stays empty
local homes = {}
player.data.homes = homes
homes.base = { x = 0, y = 64, z = 0 }

-- Right: assign first, then work with the saved table
player.data.homes = player.data.homes or {}
local homes = player.data.homes
homes.base = { x = 0, y = 64, z = 0 }
```

The table you read back from storage is the saved one, so changes to it (at any depth) are saved.

## Data Validation

The following types are **not** allowed in storage and will raise an error:
- Functions
- Userdata
- Threads
- Tables with cyclic references

## Persistence

Data is written to disk on:
- **Server stop**
- **Player disconnect** (per-player data only)
- **`/ignis reload`**

Per-player data is removed from memory on disconnect (but remains on disk).
