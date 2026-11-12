# Lua environment

- **Runtime**: PxLuaNova (Lua 5.2), composite build from `pxluanova/`.
- **Config dir**: `config/ignis/` (`.lua` files sorted alphabetically). Falls back to `config/ignis.lua`. First run
  copies `demo.lua` from resources.
- **`package.path`**: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`.
- **Loaded libs**: `math`, `string`, `table`, `bit32`, `package` (custom), `coroutine`. **Not loaded**: `io`,
  `os`, `debug`.
- **Globals injected**: `mc` (table), `vec(x,y,z)`, `register(syntax, handler, permission?)`.
- **Built-in Lua libs** (via `require`): `format`, `simple`, `chestgui`.
- **Scheduler**: ticked via `ServerTickEvents.END_SERVER_TICK`. Tasks cleared on reload and server stop.
- **Parallel workers** (`async.parallel`, package `ru.pyxiion.ignis.parallel`): one `LuaState` per pool thread, built on
  that thread by `installStdlib` (the same function builds the main state, so both have identical stdlib globals;
  `mc`, `register` are main-only). Values cross as `Snap`s (`Snapshotter` → `Materializer`); stdlib
  tables/functions cross as paths (`StdlibRegistry`). Invariant: only `table.freeze` (and the `vec` metatable) may call
  `LuaTable.freeze()`; a frozen table has no metatable and holds only primitives and frozen tables, which is what
  makes passing it by reference safe. Shared state lives only in `SharedObject`s. The string metatable
  is per `LuaState` (`LuaState.stringMetatable`).

## Globals

Assigning a global is allowed (`function handler() ... end` at top level defines one). Reading a global that was never
assigned is an error (`global 'x' is not defined`); probe an optional global with `rawget(_G, "x")`. The guard is a
`__index` on the main state's `_G` installed last in `ScriptEnvironment.rebuild` (`installStrictGlobals`); workers
install their own.
