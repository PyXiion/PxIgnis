# Lua environment

- **Runtime**: PxLuaNova (Lua 5.2), composite build from `pxluanova/`.
- **Config dir**: `config/ignis/` (`.lua` files sorted alphabetically). Falls back to `config/ignis.lua`. First run
  copies `demo.lua` from resources.
- **`package.path`**: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`.
- **Loaded libs**: `math`, `string`, `table`, `bit32`, `package` (custom), `coroutine`, `nova`. **Not loaded**: `io`,
  `os`, `debug`.
- **Globals injected**: `mc` (table), `vec(x,y,z)`, `register(syntax, handler, permission?)`.
- **Built-in Lua libs** (via `require`): `format`, `simple`, `chestgui`.
- **Scheduler**: ticked via `ServerTickEvents.END_SERVER_TICK`. Tasks cleared on reload and server stop.

## Lambda syntax (opt-in: `--# nova syntax` on line 1)

- `\{ x, y -> return x + y }` → `function(x, y) return x + y end`.
- `\{ return 42 }` — explicit return required.
- Trailing block: `register("x") \{ ctx -> ... }` desugars to second arg. Trailing bodies are chunks (no implicit
  return).
- `\{` is a two-char lexer token. Bare `\` outside string = syntax error.

Implicit return was removed because of codegeneration issues.
