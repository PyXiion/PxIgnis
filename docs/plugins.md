# Plugins and script packages

Status: implemented (core: `plugin/`, `packages/`, `sandbox/LuaPackage.kt`; first plugin: `ignis-voice`).

Two extension layers:

- **Plugins**: separate Fabric mods that add Lua modules backed by Kotlin. The first one is `ignis-voice` ([voice.md](./voice.md)).
- **Script packages**: folders of Lua code with a manifest, a basic version of issue #5. Packages can declare that they
  need a plugin.

## Plugins

### Discovery and lifecycle

A plugin is a Fabric mod with the entrypoint key `pxignis` that implements `IgnisPlugin`. It sets
`depends: { "pxignis": "~0.N" }`. Ignis is pre-1.0, so there is no `apiVersion` handshake: the dependency pin is the
contract.

```kotlin
interface IgnisPlugin {
    val id: String                            // [a-z0-9_-]+, the namespace of modules and events
    fun onServerStart(ctx: PluginContext) {}  // once per server start
    fun onEnvironment(env: PluginEnv)         // on every ScriptEnvironment.rebuild, before scripts run
    fun onServerStop() {}
}

interface PluginContext { val server: MinecraftServer; val scope: CoroutineScope
                          val scriptRoots: List<Path>; val errors: ScriptErrors }
```

`onEnvironment` runs on every reload because the Lua state is rebuilt each time (see AGENTS.md). Plugins must not
hold a `LuaState` or Lua values across reloads. Whatever they allocate for a given environment they release through
`env.onClose`. `onClose` blocks run in `IgnisRuntime.reload` after the `uninit` event and before the new state is
built, and at server stop (before `onServerStop`); a failing block or plugin is reported and does not stop the rest.
An exception in `onEnvironment` disables that plugin's modules for this environment only.

```kotlin
interface PluginEnv {
    val id: String; val server: MinecraftServer; val scope: CoroutineScope
    val convert: LuaConvert; val errors: ScriptErrors
    fun module(name: String? = null, build: () -> LuaValue)  // lazy; null = require("ext:<id>"), else "ext:<id>.<name>"
    fun metaTable(meta: BuiltMeta): LuaTable                 // fresh metatable from the core builder DSL
    fun newObject(meta: LuaTable, type: String, obj: Any): LuaTable
    fun extend(type: String, name: String, fn: LuaValue)     // adds "<id>_<name>" to a core wrapper metatable
    fun emit(event: String, vararg args: LuaValue): Boolean  // only "<id>:*"; server thread only; false = cancelled
    fun onClose(block: () -> Unit)                           // runs on reload and server stop
    fun file(path: String): Path                             // sandboxed, resolved against the calling package
    fun runOnServer(block: () -> Unit)                       // hop to the server thread (AsyncLib.dispatch)
    fun function(body: (Varargs) -> Varargs): LuaFunction
    fun suspendFunction(block: suspend (Varargs) -> Varargs): LuaFunction  // yields the coroutine, resumes on the server thread
}
```

**`file` and the caller.** A Kotlin function cannot see its Lua caller, so the core copies each module table per
calling package (plain functions of it, nested plain tables included, are wrapped to set the caller for the duration
of the call). `env.file(path)` reads it. Functions that extend `LuaContinuableFunction` and objects with a metatable
are passed through unwrapped, so call `file` directly from a function of the module table. A missing file or a path
that leaves the folder is an `IOException`.

### Module names: `ext:`

- `require("ext:voice")` is the plugin's main module, and `require("ext:voice.<name>")` is a submodule.
- The prefix keeps plugin modules from colliding with user files (`voice.lua`) and with `core:`.
- Plugins add no globals. Strict globals stay as they are.
- If the plugin is not installed, `require` fails with `plugin 'voice' is not installed`. Scripts that can run without
  it use `pcall(require, "ext:voice")`. Packages declare the dependency in their manifest instead.

### LuaConvert: the stable value boundary

- Lua to Minecraft: `toPlayer(v)`, `toEntity(v)`, `toLevel(v)`, `toVec(v)`, `toItem(v)` (a copy); each takes an optional
  argument name for the error message.
- Minecraft to Lua: `wrap(player | entity | level | vec3 | stack)`.
- Errors name the argument and the expected type: `bad argument 'player': player expected, got nil`.
- Options-table helpers: `options(v, fn)` returns `Opts` with `float`, `double`, `int`, `boolean`, `string`,
  `entity`, `player`, `level`, `vec`, `function`, `list`, `has`, `rejectUnknown`; errors read
  `<fn>: option 'distance' must be a number, got string`.

### Extending core wrappers (A3)

Allowed. The method name gets the `<id>_` prefix, for example `player:voice_play(...)` from `extend("player", "play", fn)`.
A name that already exists is a load error. It is applied to the fresh metatable of exactly that type on every rebuild
(`player` does not inherit what is added to `entity`). Not used by `ignis-voice` v1.

### luaj packages

The packages inside the pxluanova fork are `ru.pyxiion.luanova.*` and the shadow jar no longer relocates them, so
plugins compile against the same names that ship.

### Lua type stubs

A plugin ships `lua-types/*.lua` in its jar. `EditorSupport` copies them to `config/ignis/.types/plugins/<id>/`. This
directory is separate because `copyTypes` wipes `.types/*.lua`.

### Repository layout

Plugins are Gradle subprojects, `plugins/<id>`, with the same `src/version-<v>` two-target layout. CI builds the core
and the plugins together.

## Script packages (basic version of issue #5)

### Layout

```
config/ignis/
  main.lua                      # loose top-level scripts keep working, unchanged
  packages/
    arena/
      ignis.json
      init.lua
      sounds/boss.ogg
```

### Manifest (`ignis.json`)

```json
{
  "id": "arena",
  "version": "1.0.0",
  "entry": "init.lua",
  "depends": {
    "pkg:shops": ">=1.0",
    "ext:voice": "*"
  },
  "optional": {
    "pkg:stats": "*"
  }
}
```

- `id`: `[a-z0-9_-]+`. A duplicate id is a load error naming both paths.
- `version` and the ranges in `depends` / `optional` use semver predicates (Fabric loader's `VersionPredicate`).
  Plugin versions come from their Fabric mod metadata.
- `entry` defaults to `init.lua`. It is the package's root module.
- `optional`: if installed, it loads first and its version is checked. If missing, the package loads anyway and gets
  `pcall(require, ...)` → false.

### Names: identity vs source

`require` always takes the **package id**, never where the package comes from:

| String | Resolves to |
|---|---|
| `require("pkg:arena")` | the `entry` of package `arena` (the root module) |
| `require("pkg:arena.ui.menu")` | `<arena folder>/ui/menu.lua` (or `ui/menu/init.lua`) |
| `require("util.strings")` | `<script root>/util/strings.lua` (unchanged, plain Lua) |
| `require("ext:voice")` | module of plugin `voice` |
| `require("core:...")` | built-in module |

Module paths use `.` everywhere, like plain Lua `require`; `/` appears only in sources (`gh:Owner/repo`), which are
never passed to `require`. The first segment after `pkg:` is always the id and the rest is a path inside the package,
so it is always clear which part is the root. Ids are `[a-z0-9_-]+`, so they contain neither `.` nor `/`.

Namespaces are a closed set: `core:`, `pkg:`, `ext:`. Any other `x:` is an error. **Breaking:** today `Vfs.resolve`
maps any unknown `ns:a.b` to the folder `<root>/ns/a/b.lua`. That fallback is removed, because it would let a user
folder named `pkg` or `ext` shadow the namespaces; such scripts switch to `require("ns.a.b")`.

Sources (local folder now; `gh:Owner/repo@tag` later) belong only to installation, never to `require`. A future
installer (`/ignis pkg install gh:PyXiion/super-module@v1.2`) downloads into `packages/<id>/`, and the id comes from
the downloaded manifest. Why not `require("gh:...")`:
- downloading during `require` would put network I/O on the server thread and make loading depend on GitHub being up;
- a version or fork switch would mean editing every `require` instead of one install;
- two sources of one package would load as two different modules.

### Loading

- Packages are executed eagerly (they register commands and events even if nobody requires them), in topological
  order of `depends` + `optional`, with ids as a deterministic tiebreak. Loose scripts load after all packages.
- `require("pkg:x")` on a package that has not run yet runs it on the spot (normal Lua `require` semantics), so an
  undeclared dependency still works no matter the order. A cycle is an error that shows the chain. Declaring a
  dependency adds a version check, an early "missing" error and the skip cascade; it is not needed for `require`.
- If a package fails (missing dependency, version mismatch, cycle, error in entry), it is skipped, and so is every
  package that declared it in `depends`. The error goes through `ScriptErrors` with the dependency chain.
- Each package's entry runs once per environment, and its return value is cached.
- Inside a package, a bare `require("util")` resolves against the package folder only. Packages cannot reach
  loose scripts' modules; other packages are reached through `pkg:`.
- Resource paths given to plugins (`voice.load("sounds/boss.ogg")`) resolve against the calling package's folder. In
  loose scripts they resolve against the script root. The path cannot escape the folder.

### Not in scope yet

`permissions` (nothing enforces them yet, and a declared permission that does nothing is false security), per-package
config, the `gh:` installer, a dependency solver, choosing between multiple versions.
