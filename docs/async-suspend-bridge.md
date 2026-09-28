# Async suspend bridge (Kotlin ↔ Lua)

Status: implemented (unreleased).

## Problem

Calling Kotlin `suspend` code from Lua used to mean one of two bad options:

- `runBlocking` on the server thread — blocks the Minecraft server for the whole duration, freezing the game.
- Starting a coroutine with no way to hand the result back to the Lua script that called it.

The goal: a Lua coroutine should **yield**, let the server thread keep running, and **resume right where it left off**
— with the suspend block's result — once the async work is done.

## Design

### `luaSuspendFunction` yields, doesn't block

`luaSuspendFunction(scope, block)` returns a `LuaContinuableFunction` (PxLuaNova's suspendable function type). When
called:

1. It captures the result in a `CompletableFuture` and yields the Lua coroutine via `YieldContinuationException`.
2. The suspend block runs on the supplied `CoroutineScope`.
3. When the block completes, the future's `handle` resumes the Lua coroutine with the result.

The Lua coroutine is a real Lua coroutine (an `LuaThread`), so the yield/resume machinery is already there — we just
need something to eventually *resume* it.

### `LuaThread.ResumeHandler` decouples *what* from *where*

The resume handler is a per-`LuaThread` callback: "when this thread should be resumed with these args, do this." This
decouples the two responsibilities:

- **What to do** (run the Kotlin block, produce a result) — owned by `luaSuspendFunction`.
- **Where to resume** (the runtime's threading model) — owned by the host.

PxIgnis sets the handler on the **main thread** (`LuaState.getMainThread()`), where it's inherited by every child
coroutine — so all coroutines get it automatically. The handler defers to the server thread:

```kotlin
LuaThread.ResumeHandler { thread, args ->
    server.run { thread.resumeOrLog(args, "async callback") }
}
```

Why store it on `LuaThread` and not `LuaState`? Because "where to resume" is a property of the *thread of execution*,
and coroutines inherit it from their parent. `LuaState` is shared across many coroutines; the handler must follow the
coroutine.

### Failure modes are explicit, not silent

A suspend function needs two preconditions: the caller must be inside a coroutine (else there's nothing to yield/resume
through) and the thread must have a resume handler (else nothing will ever resume it). Both fail with a clear
`LuaError` instead of hanging forever.

### Async functions on the server thread

PxLuaNova's `LuaState.setCurrent(state)` is normally only set by the async coroutine runner (`State.run()`). The
synchronous path (server thread) never set it, so `LuaState.current()` returned `null` during Lua execution. `lua_resume_sync`
now sets it on entry and restores it in `finally`, making both execution paths consistent.

## Threading model

- Suspend blocks run on `IgnisRuntime.modScope` (`SupervisorJob() + Dispatchers.Default`).
- Resumes are dispatched back to the **server thread** via `server.run { ... }` — never resumed synchronously from a
  callback thread.
- The mod scope is cancelled on `SERVER_STOPPING` so in-flight coroutines die with the server; a `SupervisorJob` means
  one failing block doesn't cancel unrelated work.

## Why event handlers run through `LuaThread`

Event handlers used to be invoked directly (`callback.invoke()`). A handler that called `mc.sleep` / `mc.fetch` (now `async.sleep` / `async.fetch`) / a
suspend function would yield across a plain call and crash. Routing `LuaClosure` handlers through a `LuaThread`
(`LuaThread(state, cb).resumeOrLog(...)`) gives event handlers the same coroutine semantics as scheduled tasks and
commands.

## What was deliberately not done

- **A thread pool for LuaThreads**: deferred. Sync-mode `LuaThread` instances are cheap and resetting a thread's
  `State` is invasive; the win is marginal for the current workload.

## Key files

- `pxluanova/pxluanova-core/src/main/java/org/luaj/vm2/LuaThread.java` — `ResumeHandler`, `lua_resume_sync`
  `LuaState.current()` fix.
- `src/main/java/ru/pyxiion/ignis/Utils.kt` — `luaSuspendFunction` / `luaSuspendFunctionNil`.
- `src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt` — main-thread resume handler, `suspendFunction` helper.
- `src/main/java/ru/pyxiion/ignis/EventBus.kt` — closure handlers via `LuaThread`.
- `src/main/java/ru/pyxiion/ignis/IgnisRuntime.kt` — `modScope`.
