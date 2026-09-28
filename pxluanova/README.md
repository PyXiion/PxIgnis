# PxLuaNova

A modernized, maintained fork of LuaJ 3.0.2 with bug fixes from [wagyourtail/luaj](https://github.com/wagyourtail/luaj), [Cobalt](https://github.com/cc-tweaked/Cobalt), and a lambda literal extension. Distributed as a composite build subproject of [PxIgnis](https://github.com/PxRP/PxIgnis).

## Features

- **Modern Java**: Requires Java 21+; coroutines are frame-based and need no threads
- **Bug fixes**: Comprehensive fixes from wagyourtail/luaj and Cobalt
- **Enhanced stdlib**: Lua 5.3+ additions (`math.type`, multi-arg `math.min`/`max`, `math.atan(y, x)`)
- **Lambda literal extension**: Opt-in per-file `\{ ... }` syntax (see below)
- **API compatible**: Drop-in replacement for LuaJ 3.0.2 (`org.luaj.vm2.*` packages)

## Quick Start

```java
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

// Create globals with standard libraries
Globals globals = JsePlatform.standardGlobals();

// Run a Lua script
globals.load("print('hello, world')").call();

// Call Lua from Java
LuaValue result = globals.load("return 2 + 2").call();
System.out.println(result.toint()); // prints 4
```

## Lambda Literal Extension

PxLuaNova adds a non-standard `\{ ... }` syntax for inline anonymous functions. Opt-in per file **on line 1**:

```lua
--# nova syntax
```

This pragma enables the `\` + `{` two-character sequence to synthesize a lambda literal. Without it, `\` outside a string is a syntax error.

### Forms

**Named-arg lambda** — the body is an expression; it is implicitly returned:

```lua
local double = \{ x -> x * 2 }
print(double(21))  -- 42
```

**Zero-arg expression** — no arrow, a single expression is implicitly returned:

```lua
local answer = \{ 40 + 2 }
print(answer())    -- 42
```

**Zero-arg chunk** — use `return` for explicit values:

```lua
local get = \{
  print("side effects OK")
  return 42
}
```

**Multi-arg lambda**:

```lua
local sum = \{ a, b, c -> a + b + c }
print(sum(1, 2, 3))  -- 6
```

**Trailing-block sugar** — `\{ ... }` after a function call appends the lambda as the last argument. The body is always parsed as a chunk (no implicit return):

```lua
register("greet") \{ ctx ->
  ctx.player:send("hello!")
}
```

Desugars to `register("greet", function(ctx) ctx.player:send("hello!") end)`.

Implementation: lexer-level synthesis in `LexState.java` (`TK_LAMBDA`/`TK_DARROW` tokens, per-file `lambdaSyntax` flag).

## Coroutines

Coroutines run on the thread that resumes them: `resume` drives a frame-based interpreter until the body
returns, errors or yields, and a yield just saves the frame stack. No Java thread is parked per coroutine, so
an abandoned coroutine is ordinary garbage.

Lua code can yield anywhere the frame interpreter runs it directly — including inside `pcall`/`xpcall` and
generic-`for` iterators. Lua code entered from Java (metamethods, `table.sort` comparators, `load` readers,
LuaJC-compiled functions) cannot yield and raises `attempt to yield across a C-call boundary`, as in Lua 5.2.

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

Reference-output tests with known differences are excluded, with the reason for each, in
`pxluanova-test/build.gradle`; everything else must pass.

## What's New

### Coroutine model
- Frame-based coroutines: `pcall`/`xpcall` run as protected frames, so yields inside them resume correctly
- Clean "attempt to yield across a C-call boundary" error instead of silently continuing
- Java functions can be coroutine bodies (run through a bytecode trampoline)

### Language extensions
- Lambda literal `\{ ... }` syntax (opt-in per file via `--# nova syntax` pragma)
- Named-arg (`->`), zero-arg expression, zero-arg chunk, and trailing-block forms
- Per-file `lambdaSyntax` flag in `LexState` (no global toggle)

### Standard library
- `math.type` — Lua 5.3+ type classification for numbers
- `math.atan(y, x)` — optional second argument (Lua 5.3+)
- Multi-arg `math.min` / `math.max`

### Build & toolchain
- Migrated from Ant to Gradle multi-module build (`core`, `jse`, `test`)
- Minimum Java version raised from 11 to 21
- Java ME (JME) support removed
- `pxluanova-jse` depends on BCEL 6.8.2 (LuaJC bytecode compiler)

### Bug fixes
- Pattern/string fixes (empty matches, `%b` patterns, ReDoS protection, stack overflow)
- Table library fixes (metatable support, bounds validation, sort speedup)
- Metamethod fixes (number/string comparison, weak tables)
- Compiler fixes (lexer bugs, error handling, varargs, `getobjname`)
- Numeric fixes (little-endian default, for-loop add order, integer sub overflow)
- Error report improvements (file/line info, missing info in LuaError)
- `tostring` on large doubles, non-ASCII coercion crash
- String format `%o`/`%g`/unsigned long handling
- `package.config` added
- DebugLib `getlocal` fix for function arguments

See [TODO.md](TODO.md) for the complete roadmap and deferred items.

## Migration from LuaJ

PxLuaNova is API-compatible with LuaJ 3.0.2. The package names remain `org.luaj.vm2.*`, so you can drop it in as a replacement.

**Breaking changes:**
- Minimum Java version raised from 11 to 21
- JME (Java ME) support removed
- Coroutines are frame-based and run on the resuming thread

## Modules

- **pxluanova-core**: Core Lua implementation (interpreter, compiler, standard libraries)
- **pxluanova-jse**: Java SE platform support (JSE libraries, luajava integration, LuaJC)
- **pxluanova-test**: JUnit 4 test suite

## Credits

- [LuaJ 3.0.2](https://github.com/luaj/luaj) — Original LuaJ implementation
- [wagyourtail/luaj](https://github.com/wagyourtail/luaj) — Bug fixes and improvements
- [Cobalt](https://github.com/cc-tweaked/Cobalt) — CC:Tweaked's Lua fork (bug fixes, stdlib enhancements, coroutine architecture reference)
- [Lua](https://www.lua.org/) — The Lua programming language

## License

The LuaJ 3.0.2 base is MIT licensed — see the [upstream LICENSE](https://github.com/luaj/luaj/blob/master/LICENSE). My bug fixes and new features (frame-based coroutines, lambda literal extension, etc.) are released under the GNU LGPL v3.0.
