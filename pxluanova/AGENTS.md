# AGENTS.md

## Build & Test Commands

```bash
./gradlew build                          # Build all modules
./gradlew test                           # Run all tests
./gradlew :pxluanova-core:build          # Build core only
./gradlew :pxluanova-test:test --tests "org.luaj.vm2.OrphanedThreadTest"  # Single test
```

## Architecture

**Three Gradle modules:**
- `pxluanova-core` — Interpreter, compiler, standard libs. Package: `org.luaj.vm2.*`
- `pxluanova-jse` — Java SE platform (JSE libs, luajava, LuaJC bytecode compiler). Depends on core.
- `pxluanova-test` — JUnit 4 test suite. Depends on both.

**Package names are `org.luaj.vm2.*`** — NOT renamed from LuaJ. This is intentional for API compatibility.

**Key files:**
- `LuaThread.java` — Coroutine implementation (virtual threads via ReentrantLock/Condition)
- `LuaClosure.java` — Main interpreter loop, errorHook
- `Globals.java` — Global environment, `running` thread, `coroutineThreadFactory`
- `JsePlatform.java` — Entry point: `standardGlobals()`, `debugGlobals()`

## Java Version

**Java 21+ required.** Not 11, not multi-release. Virtual threads are the default for coroutines.

## Test Quirks

- **Working directory**: Tests run from `pxluanova-test/src/test/`, not project root. Lua test scripts are at `pxluanova-test/src/test/lua/*.lua`.
- **40 known test failures** (not regressions):
  - 13 `CompatibiltyTest` — need native Lua binary for output comparison / timezone differences
  - 6 `ErrorsTest` — error message format changes from our fixes
  - 21 `WeakTableTest` — pre-existing bug in weak table array part handling
- **ScriptDrivenTest** compares output against reference Lua scripts. Tests look for files in `lua/` subdir (not `test/lua/`).
- **Zip fallback**: Tests can load from `luaj3.0-tests.zip` if plain files not found.

## Reference Repositories (gitignored)

These are local clones for reference only, not built:
- `luaj-upstream/` — Original LuaJ 3.0.2
- `wagyourtail-luaj/` — Bug fixes (Enyby batch)
- `cobalt-upstream/` — CC:Tweaked's Lua fork (architecture reference for coroutines)

## Coroutine Model

Coroutines use **virtual threads by default** (Java 21+). Each coroutine gets a virtual thread (~1-10KB). Platform threads available via opt-out:

```java
globals.coroutineThreadFactory = LuaThread.PLATFORM_THREAD_FACTORY;
```

The handoff between resumer and coroutine uses `ReentrantLock` + `Condition` (not `synchronized`/`wait()`/`notify()`). The `run()` method holds the lock for the entire Lua execution; `lua_yield()` and `lua_resume()` use `condition.await()`/`condition.signal()` to hand off.

## Lambda literal extension

PxLuaNova adds a non-standard `\{ ... }` lambda syntax (off by default, opt-in per file via `--# nova syntax` on line 1):
- `\{ a, b -> return a + b }` — named-arg lambda, desugars to `function(a, b) return a + b end`.
- `\{ return 42 }` — zero-arg lambda, requires explicit `return`.
- `register("lambda") \{ ctx -> ... }` — trailing block sugar, desugars to `register("lambda", function(ctx) ... end)`. Trailing bodies are always chunks (no implicit return).

Implemented in `LexState.java` (lexer synth of `TK_LAMBDA`/`TK_DARROW`, `lambdaBody()` helper, `llex()` magic-comment detection on line 1, hooks in `simpleexp`/`funcargs`/`suffixedexp`). Per-file state lives in the `LexState.lambdaSyntax` instance field; the global `LuaC.lambdaSyntax` flag no longer exists.

## Dependencies

- `pxluanova-jse` depends on `org.apache.bcel:bcel:6.8.2` (Lua-to-Java bytecode via LuaJC)
- `pxluanova-test` uses JUnit 4.13.2

## What NOT to Change

- **Don't rename packages** — `org.luaj.vm2` is intentional for drop-in LuaJ replacement
- **Don't add JME code** — Java ME support was deliberately removed
- **Don't change `synchronized` in DebugLib** — those are per-coroutine, no contention, won't pin virtual threads
