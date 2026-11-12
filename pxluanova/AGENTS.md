# AGENTS.md

## Build & Test Commands

```bash
./gradlew build                          # Build all modules
./gradlew test                           # Run all tests
./gradlew :pxluanova-core:build          # Build core only
./gradlew :pxluanova-test:test --tests "org.luaj.vm2.CoroutineYieldTest"  # Single test
```

## Architecture

**Three Gradle modules:**
- `pxluanova-core` — Interpreter, compiler, standard libs. Package: `org.luaj.vm2.*`
- `pxluanova-jse` — Java SE platform (JSE libs, luajava, LuaJC bytecode compiler). Depends on core.
- `pxluanova-test` — JUnit 4 test suite. Depends on both.

**Package names are `org.luaj.vm2.*`** — NOT renamed from LuaJ. This is intentional for API compatibility.

**Key files:**
- `LuaThread.java` — Coroutine state; `resume` runs `FrameInterpreter` on the caller's thread
- `FrameInterpreter.java` — Frame-based interpreter for coroutines (yields, protected pcall/xpcall frames)
- `LuaClosure.java` — Recursive interpreter for Lua entered from Java (not yieldable), errorHook
- `LuaState.java` — Global environment, current thread, interrupts/checkpoints
- `JsePlatform.java` — Entry point: `standardGlobals()`, `debugGlobals()`

## Java Version

**Java 21+ required.** Not 11, not multi-release.

## Test Quirks

- **Working directory**: Tests run from `pxluanova-test/src/test/`, not project root. Lua test scripts are at `pxluanova-test/src/test/lua/*.lua`.
- **The suite must be green.** Reference-output tests with known differences are excluded one by one, each with
  its reason, in `pxluanova-test/build.gradle`. Fix a difference → delete its entry. CI runs `./gradlew test` here.
- **ScriptDrivenTest** compares output against reference Lua scripts. Tests look for files in `lua/` subdir (not `test/lua/`).
- **Zip fallback**: Tests can load from `luaj3.0-tests.zip` if plain files not found.

## Reference Repositories (gitignored)

These are local clones for reference only, not built:
- `luaj-upstream/` — Original LuaJ 3.0.2
- `wagyourtail-luaj/` — Bug fixes (Enyby batch)
- `cobalt-upstream/` — CC:Tweaked's Lua fork (architecture reference for coroutines)

## Coroutine Model

Coroutines are synchronous: `LuaThread.resume` → `State.lua_resume_sync` → `FrameInterpreter.run` on the
caller's thread. A yield sets `yieldRequested`, the interpreter returns, and the frame stack stays in `State`
until the next resume. There are no coroutine threads (the old virtual-thread model was removed).

- **Lua→Lua calls** in a coroutine push `LuaFrame`s; nothing recurses in Java, so they can yield.
- **pcall/xpcall** (`ProtectedCall`) with any target run as protected frames: `FrameInterpreter.recover`
  unwinds errors to the nearest one and delivers `false, msg`; a normal return gets a leading `true`.
  Non-closure targets (and non-closure coroutine bodies) run through the `TRAMPOLINE` prototype.
- **Lua entered from Java** (`LuaClosure.execute`, `TailcallVarargs.eval`) bumps `State.nonYieldableDepth`;
  yielding there (`lua_yield_sync` or a `YieldContinuationException`) is "attempt to yield across a C-call
  boundary". `LuaContinuableFunction`s called straight from the frame interpreter can still yield.

## Checkpoints (instruction polling)

Both interpreters (`FrameInterpreter.step`, `LuaClosure.execute`) decrement `LuaState.checkpointCountdown` per
instruction and call `LuaState.checkpoint()` every `checkpointInterval` instructions (default 100) — nothing heavier
runs per instruction. `checkpoint()` services `LuaState.interrupt()` requests and polls the optional
`Builder.checkpointHandler` (CONTINUE / SUSPEND / throw `LuaError`), which hosts use for time limits. The countdown is
per-state and unsynchronized on purpose, and it carries across calls so short functions (e.g. infinite tail
recursion) are still covered.

LuaJC-compiled code has no interpreter loop: `JavaBuilder.addBranch` emits a call to the static
`LuaState.compiledBackwardJump()` before every backward branch, and `TailcallVarargs.eval` calls it per tail
call; it runs `checkpoint()` on `LuaState.current()` every 100 calls.

## Fork-specific behaviour

- **String metatable is per `LuaState`.** `LuaString.getmetatable()` reads `LuaState.current().stringMetatable`; `StringLib` sets it on the state that loads it (no more static `LuaString.s_metatable`). Code that runs Lua without a current state sees no string metatable.
- **Frozen tables.** `LuaTable.freeze()` is shallow and permanent; afterwards `presize`, `setmetatable`, `rawset`, `hashset` and `sort` throw `attempt to modify a frozen table` (so `set`, `insert`, `remove`, `OP_SETLIST` too). Reads are unaffected, so a frozen table without a metatable can be read from several threads. Deep rules (what may be reachable from a frozen table) are the embedder's job.

## Dependencies

- `pxluanova-jse` depends on `org.apache.bcel:bcel:6.8.2` (Lua-to-Java bytecode via LuaJC)
- `pxluanova-test` uses JUnit 4.13.2

## What NOT to Change

- **Don't rename packages** — `org.luaj.vm2` is intentional for drop-in LuaJ replacement
- **Don't add JME code** — Java ME support was deliberately removed
- **Don't change `synchronized` in DebugLib** — those are per-coroutine and uncontended
