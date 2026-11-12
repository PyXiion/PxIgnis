# PxLuaNova

A modernized, maintained fork of LuaJ 3.0.2 with bug fixes from [wagyourtail/luaj](https://github.com/wagyourtail/luaj) and [Cobalt](https://github.com/cc-tweaked/Cobalt). Distributed as a composite build subproject of [PxIgnis](https://github.com/PxRP/PxIgnis).

## Features

- **Modern Java**: Requires Java 21+ — virtual threads for coroutines by default
- **Bug fixes**: Comprehensive fixes from wagyourtail/luaj and Cobalt
- **Enhanced stdlib**: Lua 5.3+ additions (`math.type`, multi-arg `math.min`/`max`, `math.atan(y, x)`)
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

## Coroutines & Virtual Threads

PxLuaNova uses Java virtual threads for coroutines by default, enabling millions of concurrent coroutines with minimal memory overhead (~1-10KB per coroutine vs ~1MB for platform threads).

```lua
-- Create 10,000 coroutines without memory issues
local threads = {}
for i = 1, 10000 do
    threads[i] = coroutine.create(function()
        coroutine.yield(i)
    end)
end

-- Resume all of them
for i = 1, 10000 do
    coroutine.resume(threads[i])
end
```

### Platform Threads (Opt-out)

```java
Globals globals = JsePlatform.standardGlobals();
globals.coroutineThreadFactory = LuaThread.PLATFORM_THREAD_FACTORY;
```

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

40 known pre-existing test failures — see [AGENTS.md](AGENTS.md) for details.

## What's New

### Coroutine model
- Virtual threads are the default coroutine implementation (Java 21+)
- `synchronized`/`wait()`/`notify()` replaced with `ReentrantLock`/`Condition` for deadlock-free handoff
- Configurable `coroutineThreadFactory` on `Globals` (virtual or platform threads)
- `globals.running` made `volatile` for cross-thread visibility

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
- Coroutines use virtual threads by default (can be disabled)

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

The LuaJ 3.0.2 base is MIT licensed — see the [upstream LICENSE](https://github.com/luaj/luaj/blob/master/LICENSE). My bug fixes and new features (virtual thread coroutines, etc.) are released under the GNU LGPL v3.0.
