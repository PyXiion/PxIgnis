---
title: Packages and plugins
description: Script packages with a manifest, and Kotlin plugins that add Lua modules.
---

Two ways to extend PxIgnis beyond loose scripts:

- **Script packages**: folders of Lua code with an `ignis.json` manifest.
- **Plugins**: separate Fabric mods that add Lua modules written in Kotlin (for example `ignis-voice`).

## `require` names

| String | Resolves to |
|---|---|
| `require("pkg:arena")` | the entry file of package `arena` |
| `require("pkg:arena.ui.menu")` | `ui/menu.lua` (or `ui/menu/init.lua`) inside the `arena` package |
| `require("util.strings")` | `util/strings.lua` of the [script root](/reference/configuration#script-roots) |
| `require("ext:voice")` | the main module of plugin `voice`; `ext:voice.<name>` is a submodule |
| `require("core:format")` | a built-in library |

Module paths use `.` between segments. The namespaces are a closed set: `core:`, `pkg:` and `ext:`. Any other
`x:` is an error; a script folder `mymod/utils.lua` is `require("mymod.utils")`. A require cycle fails with the chain
(`circular require: pkg:a -> pkg:b -> pkg:a`).

## Script packages

```
config/ignis/
  main.lua                  loose scripts keep working
  packages/
    arena/
      ignis.json
      init.lua
      ui/menu.lua
```

```json
{
  "id": "arena",
  "version": "1.0.0",
  "entry": "init.lua",
  "depends": { "pkg:shops": ">=1.0", "ext:voice": "*" },
  "optional": { "pkg:stats": "*" }
}
```

- `id` is `[a-z0-9_-]+`; two packages with the same id are an error that names both folders, and neither loads.
- `version` and the ranges are semver predicates (`>=1.0`, `~0.3`, `1.x`, `*`). The version of an `ext:` dependency is
  the version of the plugin's mod.
- `entry` defaults to `init.lua`.
- `optional`: when installed, it loads first and its version is checked; when missing, the package still loads and
  `pcall(require, "pkg:stats")` returns false.

### Loading

Packages run when scripts load (and on every reload), before the loose scripts, dependencies first (`depends` and
`optional`), ids as a tiebreak. `require("pkg:x")` on a package that has not run yet runs it on the spot, so an
undeclared dependency still works; declaring it adds the version check, an early "missing" error and the skip rule below.

A package that fails (missing or mismatched dependency, a dependency cycle, an error in its entry) is skipped, and so
is every package that lists it in `depends`. Each failure is reported with the reason, for example
`dependency 'shops' failed: ...`. Packages that only list it in `optional` still load.

Inside a package, `require("util")` resolves in the package folder only. It cannot see the loose scripts' modules;
other packages are reached through `pkg:`. Globals a package assigns are shared like any script's.

## Plugins

A plugin is a Fabric mod. If it is not installed, `require("ext:id")` fails with `plugin 'id' is not installed`; use
`pcall(require, "ext:id")` for optional use, or list it in a package's `depends`. The editor stubs of a plugin are
copied to `config/ignis/.types/plugins/<id>/`.

### Writing a plugin

Add the entrypoint key `pxignis` and depend on `pxignis`:

```json
{
  "entrypoints": { "pxignis": [ { "adapter": "kotlin", "value": "my.plugin.MyPlugin" } ] },
  "depends": { "pxignis": "~0.19" }
}
```

```kotlin
class MyPlugin : IgnisPlugin {
    override val id = "demo"                       // [a-z0-9_-]+: ext:demo, events demo:*, methods demo_*

    override fun onEnvironment(env: PluginEnv) {   // on every script reload, before scripts run
        env.module {
            LuaTable().also { it.set("greet", env.function { a -> LuaValue.valueOf("hi " + a.checkjstring(1)) }) }
        }
        env.onClose { /* release what was allocated for this environment */ }
    }
}
```

- The Lua state is rebuilt on every reload. Do not keep a `LuaState` or Lua values across `onEnvironment` calls;
  everything allocated for an environment is released in `env.onClose` (runs on reload, before the new state is built,
  and on server stop; one failing block does not stop the others).
- `env.module(name?) { ... }` registers `ext:<id>` (or `ext:<id>.<name>`); it is built lazily on the first `require`.
- `env.file(path)` resolves a resource path against the folder of the calling package (the script root for loose
  scripts); the path cannot leave that folder. Call it directly from a function of the module table.
- `env.extend(type, name, fn)` adds `<id>_<name>` to a core wrapper (`player`, `entity`, `world`, `item`, ...); a name
  that already exists is a load error.
- `env.emit("<id>:event", ...)` posts a custom event (server thread only; only your own namespace).
- `env.convert` (`LuaConvert`) turns Lua values into `ServerPlayer`, `Entity`, `ServerLevel`, `Vec3`, `ItemStack` with
  core-style argument errors, wraps Minecraft objects for Lua and reads options tables.
- `env.runOnServer { }` and `env.suspendFunction { }` bring background work back to the server thread.
- An exception in `onEnvironment` is reported for that plugin; the other plugins and the scripts still load.
