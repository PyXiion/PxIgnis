# PxIgnis

![version](https://img.shields.io/badge/version-0.14.1-purple)
![version](https://img.shields.io/badge/MC-1.21.10-green)
![version](https://img.shields.io/badge/MC-1.21.11-green)

A Lua-scriptable roleplay command framework for Minecraft Fabric servers. Define custom chat commands and complex server logic using Lua scripts — no Java or Kotlin mod code required.

> This project is developed with the assistance of AI. Humans were harmed (and included) during development too.

Full documentation at **[ignis.pyxiion.ru](https://ignis.pyxiion.ru)**.

## Features

- **Lua-driven commands** — Write `.lua` files to register Brigadier commands with tab completion, argument parsing, and permission checks.
- **Event system** — React to 23 game events: player join/leave/respawn/death/chat/kill/damage/hurt, block break/place, item use, entity attack/interact/spawn/despawn/hurt/damage/death, and server lifecycle with Lua handlers (10 cancellable).
- **Dynamic reload** — `/ignis reload` re-executes all Lua scripts instantly without restarting the server. All Lua state is torn down and rebuilt — persistent data must use `mc.data`/`player.data`.
- **Rich argument types** — Supports `text`, `word`, `target`/`player`, `int`, `double`, `float`, `bool`, `block_pos`, and custom choices (`choice=a,b,c`) with validation.
- **Minecraft API exposed to Lua** — Trigger particles, sounds, global/range broadcasting, block manipulation, entity spawning, world time/weather control, and server time access.
- **Persistent data storage** — Key-value data per player (`ctx.player.data`) and globally (`mc.data`), auto-persisted to JSON.
- **Permission system** — Integrates with the Fabric Permissions API (supports both OP-based and permissions plugins like LuckPerms). Check permissions at runtime with `player:hasPermission(perm)`.
- **Player context** — Handlers receive a live `Player` wrapper object with readable properties (health, position, gamemode, etc.) and methods (`sendMessage`, `teleport`, `kick`, `give`, `hasPermission`).
- **Structure loading** — Load and place Minecraft structure files with rotation, mirroring, and per-entity Lua callbacks.
- **Vector API** — `vec(x, y, z)` global constructor with arithmetic operators (`+`, `-`, `*`, `/`, `unm`, `==`, `tostring`). Component-wise for `v1 * v2`, scalar for `v / n`. Both `v * n` and `n * v` work.
- **Editor autocomplete** — Ships a [LuaLS](https://luals.github.io/) type library for VS Code. Open the repo root or copy `lua-types/` next to your scripts to get IntelliSense for `mc.*`, all wrappers, events, and `register()`. See the [setup guide](https://ignis.pyxiion.ru/guide/autocomplete).
- **Entity API** — `entity:damage(amount, source?)`, `entity:raycast(range)`, `entity:addEffect/removeEffect/hasEffect`, `entity:setOnFireFor(ticks)`, `entity:readNbt()/writeNbt(table)`.
- **Debug dumping** — `mc.dump(obj, depth?)` prints any Lua value as readable nested output with cycle detection.
- **Metatable extensions** — `mc.getMetatable("player"/"entity"/"world"/"structure"/"vec")` allows adding custom methods to all wrappers of that type.
- **Per-player sidebar** — `player.sidebar` smart property: assign a config table to create/update (`player.sidebar = {title=..., lines=...}`), read back the sidebar object with `title`/`lines` properties and `setLine`/`show`/`hide`/`destroy` methods. Packet-based scoreboard display that does not touch the global scoreboard.
- **Async API** — `mc.fetch(url)` / `mc.fetch({...})` for HTTP requests (GET, POST, PUT, PATCH, DELETE, HEAD) with response table (`.ok`, `.status`, `.text`, `.headers`, lazy `.json`). `mc.sleep(ticks)` for coroutine-based sequential async without callback nesting. JSON auto-encoding for request bodies and lazy decoding for responses.
- **Lua libraries** — Bundled `format.lua` (f-string-like templating), `simple.lua` (concise command registration), and `chestgui.lua` (chest-based GUI with grid positioning).

## Quick Start

- Minecraft 1.21.x, Fabric Loader ≥0.19.2, Fabric API ≥0.141.4, Fabric Language Kotlin ≥1.10.8

1. Install the mod on your Fabric server.
2. On first run, `config/ignis/demo.lua` is created with example scripts.
3. Run `/ignis reload` (requires operator level 4 or `px.ignis` permission) to apply changes.

## License

GNU Lesser General Public License v3.0. See [LICENSE.txt](/search?q=LICENSE.txt).
