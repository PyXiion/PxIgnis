# API surface (site reference)

When writing scripts, prefer linking to docs over source code.
When updating the API, always ask user if he wants to update the documentation (site) & lua-types (<project>/lua-types/*.lua).

| Topic                     | File                                                                                             |
|---------------------------|--------------------------------------------------------------------------------------------------|
| All events (mc.on)        | [`EventCatalog.kt`](src/main/java/ru/pyxiion/ignis/events/EventCatalog.kt), fields in [`GameEvents.kt`](src/main/java/ru/pyxiion/ignis/events/GameEvents.kt) (also `/reference/events` in site docs) |
| mc.\* API                 | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt)                                  |
| register() syntax + types | [`CommandSyntax.kt`](src/main/java/ru/pyxiion/ignis/commands/CommandSyntax.kt)                   |
| **Full docs**             | **ignis.pyxiion.ru**                                                 |

`register("syntax", function(ctx))` does NOT have `ctx.args`. It uses positional args.
For `register("cmd <arg1:word> <arg2:player>", handler)` handler is `(ctx, arg1, arg2)`.

Event handlers get one table `e` (`mc.on("block.break", function(e) ... e:cancel() end, { priority = "high" })`).
Old underscore names (`player_join`, ...) still work with positional args but are deprecated.
