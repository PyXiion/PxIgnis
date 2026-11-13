# API surface (site reference)

When writing scripts, prefer linking to docs over source code.
When updating the API, always ask user if he wants to update the documentation (site) & lua-types (<project>/lua-types/*.lua).

| Topic                     | File                                                                                             |
|---------------------------|--------------------------------------------------------------------------------------------------|
| All events (mc.on)        | [`EventCatalog.kt`](src/main/java/ru/pyxiion/ignis/events/EventCatalog.kt), fields in [`GameEvents.kt`](src/main/java/ru/pyxiion/ignis/events/GameEvents.kt) (also `/reference/events` in site docs) |
| mc.\* API                 | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt)                                  |
| Structures (capture/save/place) | [`StructureWrap.kt`](src/main/java/ru/pyxiion/ignis/api/wrapper/StructureWrap.kt) (`save`, `place`), `WorldWrap.captureStructure`; pure parts (bounds, 500 000-block cap, save path, root choice) in [`StructureCapture.kt`](src/main/java/ru/pyxiion/ignis/api/util/StructureCapture.kt); writes go through `Vfs.writeTarget` (per-root symlink checks) |
| Voice plugin (`ext:voice`) | [`VoiceModule.kt`](plugins/voice/src/main/kotlin/ru/pyxiion/ignis/voice/VoiceModule.kt), types in [`voice.lua`](plugins/voice/src/main/resources/lua-types/voice.lua), design `docs/voice.md`, site `/reference/voice` |
| register() syntax + types | [`CommandSyntax.kt`](src/main/java/ru/pyxiion/ignis/commands/CommandSyntax.kt)                   |
| **Full docs**             | **ignis.pyxiion.ru**                                                 |

`register("syntax", function(ctx))` does NOT have `ctx.args`. It uses positional args.
For `register("cmd <arg1:word> <arg2:player>", handler)` handler is `(ctx, arg1, arg2)`.

Event handlers get one table `e` (`mc.on("block.break", function(e) ... e:cancel() end, { priority = "high" })`).
Event names are checked: a built-in name or a namespaced custom name (`mymod:boss_killed`); anything else is an error.
