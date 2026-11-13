# Lua environment

- **Runtime**: PxLuaNova (Lua 5.2), composite build from `pxluanova/`.
- **Script roots**: `script_roots` in `config/pxignis.json`, default `["config/ignis"]`. Relative entries resolve
  against the server run dir, absolute ones are allowed. Roots load in the listed order, `.lua` files sorted
  alphabetically inside a root; a missing root is skipped with a warning. First run copies `demo.lua` from resources
  into the first root.
- **Preload scripts**: `*.preload.lua` at any depth of a root (`ScriptRoots.preloadFiles`); skipped by `ScriptLoader`
  and `ScriptWatcher`. `preload/Preload.kt` runs them lazily when the first server `PackRepository` is built
  (`PackRepositoryMixin` → `PreloadPack.newSource()`), one fresh state per file: stdlib + `require` + strict globals +
  the `preload` table (`preload.dimension` only, see `DimensionSpec.kt`); no `mc`. A failing file is reported via
  `ScriptErrors` and contributes nothing. Declarations keep their `Source(root, file)`; `PreloadPack` writes one data
  pack per source into `config/pxignis/preload-packs` (dimension + dimension_type JSON; `Compat.HAS_WORLD_CLOCKS`
  selects the 26.x type fields). `IgnisRuntime.reload` only calls `Preload.warnIfChanged()`. Types:
  `lua-types/20-preload.lua`; user docs `site/.../reference/preload.md`.
- **`package.path`**: `<root>/?.lua;<root>/?/init.lua` for every root in order; the trailing `?.lua` (working dir) is
  not exposed. `require` searches the same roots in the same order, so the first root wins for a duplicate module and a
  module must stay inside the root it was resolved from (symlinks included).
- **Loaded libs**: `math`, `string`, `table`, `bit32`, `package` (custom), `coroutine`. **Not loaded**: `io`,
  `os`, `debug`.
- **Globals injected**: `mc` (table), `vec(x,y,z)`, `register(syntax, handler, permission?)`.
- **Built-in Lua libs** (via `require`): `format`, `simple`, `chestgui`, `items`, `particles`, `dialog`, `display`, always as
  `core:<name>`.
- **`require` names** (`sandbox/ModuleNames.kt` parses, `sandbox/LuaPackage.kt` loads): a closed namespace set
  `core:`, `pkg:`, `ext:`; any other `x:` is an error (no `ns:a.b` folder fallback). `pkg:id` is a package's entry,
  `pkg:id.a.b` a file inside it, `ext:id[.sub]` a plugin module, a plain `a.b` is `<root>/a/b.lua` (or
  `a/b/init.lua`). Cache keys in `package.loaded` are canonical names. A require cycle fails with the chain.
- **Script packages** (`packages/`): `config/ignis/packages/<folder>/ignis.json` (`id`, `version`, `entry`,
  `depends`, `optional`; ranges are Fabric `VersionPredicate`s, `ext:` ranges are checked against the plugin mod's
  version). `PackageManifest` parses, `PackagePlan` (pure) decides order (Kahn, id tiebreak over `depends` +
  `optional`), failures, cascade (`depends` dependents) and cycles; `PackageDiscovery` scans every script root's
  `packages/`; `PackageLoader` runs them eagerly in `IgnisRuntime.reload` after `ScriptEnvironment.rebuild` and
  before the loose scripts. Package code runs in its own environment table (`__index`/`__newindex` = `_G`) whose
  `require` resolves plain names inside the package folder only (the env is a `PackageEnv`, which
  `async.parallel`'s `Snapshotter`/`ProtoScan` treat as the globals). A failing entry is reported by `LuaPackage` through `ScriptErrors` and skips
  its dependents. A manifest edit is picked up by `ScriptWatcher`.
- **Plugins** (`plugin/IgnisPlugin.kt`, design `docs/plugins.md`): Fabric entrypoint key `pxignis`. `PluginManager`
  discovers them at server start (`IgnisRuntime.plugins`), calls `onEnvironment` from `ScriptEnvironment.rebuild`
  right after `mc` is set (the metatables from `MetaTableRegistry.init()` are fresh at that point, so `extend` is
  per environment), and runs `onClose` blocks in `IgnisRuntime.reload` right after the `uninit` event and before the
  new state is built, and again at shutdown. A plugin's module tables are copied per calling package with plain
  functions wrapped so `PluginEnv.file(path)` knows the caller; `LuaContinuableFunction`s and objects with a
  metatable are passed through. Plugin `lua-types/` are copied to `.types/plugins/<id>/` (`EditorSupport`).
  Authoring a plugin: implement `IgnisPlugin`, register `"pxignis": [{"adapter": "kotlin", "value": "..."}]`, depend on
  `pxignis`, keep no Lua values across `onEnvironment` calls, put every release in `env.onClose`, ship Lua stubs in
  `lua-types/*.lua`.
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
