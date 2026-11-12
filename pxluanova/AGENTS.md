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
- **49 known test failures** (not regressions; the fork's Gradle 8.10 needs `JAVA_HOME` = JDK 21):
  - 14 `CompatibiltyTest` — need native Lua binary for output comparison / timezone differences
  - 7 `ErrorsTest` — error message format changes from our fixes
  - 21 `WeakTableTest` — pre-existing bug in weak table array part handling
  - 5 `OrphanedThreadTest` — `cannot resume non-LuaClosure coroutine` (sync coroutines only run Lua closures)
  - 1 `JavaFunctionYieldTest.testJavaFunctionMultipleYieldsViaLuaCalls`
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

## Fork-specific behaviour

- **String metatable is per `LuaState`.** `LuaString.getmetatable()` reads `LuaState.current().stringMetatable`; `StringLib` sets it on the state that loads it (no more static `LuaString.s_metatable`). Code that runs Lua without a current state sees no string metatable.
- **Frozen tables.** `LuaTable.freeze()` is shallow and permanent; afterwards `presize`, `setmetatable`, `rawset`, `hashset` and `sort` throw `attempt to modify a frozen table` (so `set`, `insert`, `remove`, `OP_SETLIST` too). Reads are unaffected, so a frozen table without a metatable can be read from several threads. Deep rules (what may be reachable from a frozen table) are the embedder's job.

## Dependencies

- `pxluanova-jse` depends on `org.apache.bcel:bcel:6.8.2` (Lua-to-Java bytecode via LuaJC)
- `pxluanova-test` uses JUnit 4.13.2

## What NOT to Change

- **Don't rename packages** — `org.luaj.vm2` is intentional for drop-in LuaJ replacement
- **Don't add JME code** — Java ME support was deliberately removed
- **Don't change `synchronized` in DebugLib** — those are per-coroutine, no contention, won't pin virtual threads
