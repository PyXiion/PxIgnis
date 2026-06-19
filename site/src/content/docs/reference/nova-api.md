---
title: Nova API
description: JIT-compile Lua functions to JVM bytecode with nova.sync for 2-10× speedups on hot paths.
---

`nova.sync` compiles a Lua function (closure) to **JVM bytecode** via LuaJC, then wraps it with a yield barrier.
The compiled version runs 2-10× faster on numeric-heavy code by eliminating interpreter dispatch overhead.

```lua
local fast = nova.sync(function(x)
    local s = 0
    for i = 1, x do
        s = s + math.sqrt(i)
    end
    return s
end)

fast(1000000)  -- runs as JVM bytecode
```

## Syntax

```lua
local fn = nova.sync(f)
```

| Arg | Type          | Description                         |
|-----|---------------|-------------------------------------|
| `f` | `function`    | Lua function (must be a LuaClosure) |

Returns a callable that behaves identically to `f` with the same signature and upvalues.

## Upvalues

Captured locals are **shared** between the original closure and the compiled version — writes
from either side are visible to the other:

```lua
local counter = 0
local fn = nova.sync(function()
    counter = counter + 1
    return counter
end)

print(fn()) -- 1
print(fn()) -- 2
print(counter) -- 2
```

Globals (`math`, `string`, etc.) are resolved via `_ENV` just like a regular closure.

## Yield barrier

`coroutine.yield()` inside a sync-compiled function throws an error:

```lua
local fn = nova.sync(function()
    coroutine.yield("nope") -- error
end)
```

This is because JVM bytecode cannot suspend and resume — the entire call runs atomically.
The barrier only activates when the function is called inside a non-main coroutine.
Direct calls from the main script or command handlers (which don't yield) work fine.

## When to use

- **Hot paths** — tight loops, numeric computations, math-heavy logic called frequently.
- **Frequent callbacks** — tick handlers, collision checks, AI behaviour steps.

## When to avoid

- One-shot callbacks (the compile cost outweighs the benefit).
- Functions that need to yield (`mc.sleep`, `mc.fetch`, `coroutine.yield`).
- Trivially simple logic (single arithmetic op, field access).

## Example

Register a `/bench` command that compares the same function interpreted vs compiled:

```lua
--# nova syntax

local function work(n)
    local x = 0.0
    for i = 1, n do
        x = x + math.sqrt(i * 3.14159) / (i % 7 + 1)
    end
    return x
end

local sync_work = nova.sync(work)

register("bench [n:int]") \{ctx, n ->
    n = n or 500000

    local t0 = mc.time()
    local r1 = work(n)
    local t1 = mc.time()

    local r2 = sync_work(n)
    local t2 = mc.time()

    local interp = t1 - t0
    local compiled = t2 - t1
    local ratio = interp / compiled

    ctx.player:sendMessage(string.format("§7[Bench] n=%d  interp=%.2fms  sync=%.2fms  (§ax%.1f§r)",
        n, interp * 1000, compiled * 1000, ratio))
}
```

## See also

- [Async API](/reference/async-api) — coroutine-based sleep and HTTP
- [Language](/reference/language) — lambda syntax (`\{ ... }`)
