# PxLuaNova TODO

## Deferred Features

### Java Interop Enhancements (from wagyourtail/luaj)
**Priority: Low** | **Reference:** https://github.com/wagyourtail/luaj

- [ ] Add direct support for Java `List` and `Map` objects as Lua-like tables
- [ ] Add per-object metatables (not just per-type) for more flexible OOP patterns
- [ ] Enable calling Java constructors directly on bound classes without needing `luajava.new`
- [ ] Fix `pairs` and `ipairs` to respect metatables properly
- [ ] Improve `setmetatable` behavior for per-object metatables

**Commits:** 95d05fb, 94aeeb6, f062b53

**Note:** These features alter Lua semantics and are more specialized. They were deferred to keep the initial release focused on bug fixes and stdlib improvements.

---

## Future Work

### Build System
- [ ] Consider migrating from Gradle to a more modern build system if needed
- [ ] Add CI/CD pipeline (GitHub Actions)
- [ ] Set up automated testing and coverage reports

### Lua Version Support
- [ ] Evaluate feasibility of Lua 5.3 support (integers as 64-bit, bitwise operators, floor division `//`)
- [ ] Consider backporting more Lua 5.3/5.4 stdlib features from Cobalt

### Performance
- [ ] Profile and optimize hot paths (LuaClosure execution, table operations)
- [ ] Consider flat array table nodes (from Cobalt) for better cache performance
- [ ] Evaluate string rope concatenation (from Cobalt) for efficient string building

### Security
- [ ] Add sandboxing improvements (interrupt handlers, memory limits)
- [ ] Review and harden pattern matching against ReDoS attacks

### Testing
- [ ] Fix test infrastructure (ScriptDrivenTest needs native Lua for comparison)
- [ ] Add more unit tests for bug fixes
- [ ] Set up fuzz testing (from Cobalt's roadmap)

### Documentation
- [ ] Write comprehensive API documentation
- [ ] Add migration guide from LuaJ to PxLuaNova
- [ ] Document all bug fixes and enhancements with references

---

## Completed

### Phase 1: Project Setup
- [x] Clone luaj/luaj as base
- [x] Migrate from Ant to Gradle multi-module build
- [x] Remove Java ME (JME) support
- [x] Rename to PxLuaNova
- [x] Fix Java 14+ compatibility issues

### Phase 2: Bug Fixes from wagyourtail/luaj (Enyby batch)
- [x] String/pattern fixes (empty matches, %b patterns, stack overflow protection)
- [x] Table library fixes (metatable support, bounds validation, sort speedup)
- [x] I/O library fixes (mode checking, stack corruption, read modes)
- [x] Metamethod fixes (number/string comparison, weak tables)
- [x] Compiler fixes (lexer bugs, error() call, varargs, getobjname)
- [x] Numeric fixes (little-endian default, for-loop add order)
- [x] package.config addition

### Phase 3: Error Reporting Improvements
- [x] File/line info on errors
- [x] Missing info in LuaError fix
- [x] Remove extra boxing on loadfile

### Phase 4: Bug Fixes from Cobalt
- [x] Pattern matching ReDoS fix (security)
- [x] tostring on large doubles
- [x] String format %o, %g, unsigned long handling
- [x] Non-ASCII number coercion crash fix
- [x] Table key corruption fix (reviewed, no direct equivalent needed)
- [x] Hash size calculations (reviewed, already correct)

### Phase 5: Stdlib Enhancements from Cobalt
- [x] math.type (Lua 5.3+ feature)
- [x] Multi-arg math.min / math.max (already present)
- [x] math.atan with optional second arg (Lua 5.3+ syntax)

### Phase 6: Remaining Fixes
- [x] LuaInteger.sub() fix for numeric for-loops
- [x] LuaError error message improvements
- [x] WeakTable final fields for thread safety
- [x] DebugLib getlocal fix for function arguments
- [x] MathLib fmod NaN handling
- [x] OsLib date format improvements

### Phase 7: Virtual Thread Migration (Java 21+) — superseded: coroutines are frame-based and synchronous now
- [x] Raise minimum Java version to 21
- [x] Replace `synchronized`/`wait()`/`notify()` with `ReentrantLock`/`Condition` in LuaThread.State
- [x] Add `ThreadFactory` interface with `VIRTUAL_THREAD_FACTORY` and `PLATFORM_THREAD_FACTORY`
- [x] Virtual threads are default for coroutines (enables millions of concurrent coroutines)
- [x] Make `globals.running` volatile for cross-carrier-thread visibility
- [x] Add `globals.coroutineThreadFactory` field (configurable, defaults to virtual threads)
- [x] Remove duplicate `coroutine_count` from CoroutineLib
