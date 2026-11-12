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

Supports numbers, strings, booleans, tables, and `nil` (deletes the key). The data tables are ordinary Lua
tables: nested writes (`data.stats.kills = 5`) just work.

## Data Validation

Values are checked when the data is **saved**, not when you assign them. A bad value makes that table's save fail
with the path to it (`data.shop.items[3]: function`); the error is logged and the other tables are still saved.
Not allowed: functions, userdata, coroutines, cyclic references, `NaN`/`Infinity`, and keys that are neither
strings nor integers.

## JSON Mapping

- A table whose keys are exactly `1..n` is written as a JSON list; any other table (including `{}`) is an object.
- An integer key of an object is written as a string (`{[1001] = 1}` → `{"1001": 1}`) and read back as an integer.
  A *string* key that looks like an integer (`"1001"`) therefore does not survive a reload: it comes back as the
  number `1001`.
- A string is always a string: `"0042"` stays `"0042"`.
- Whole numbers are stored as integers (up to 64 bits), everything else as doubles.

## Persistence

Data is written to disk on:
- **Server stop**
- **Player disconnect** (per-player data only)
- **`/ignis reload`**
- **Every 5 minutes** (only tables that changed are rewritten)

Per-player data is removed from memory on disconnect (but remains on disk).

A file that cannot be parsed is renamed to `<name>.corrupt-<timestamp>` and the data starts empty; the original
is never overwritten.
