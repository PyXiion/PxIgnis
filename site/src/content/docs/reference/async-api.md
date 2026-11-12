---
title: Async API
description: Non-blocking waits, tasks, promises, and combining them.
---

Lua scripts run on the Minecraft server thread. `async` lets a coroutine **wait** for something (ticks, an HTTP
response, another task) without blocking the server, and `async.parallel` runs a function on a pool thread when a
calculation is too heavy for the tick.

- **Wait for I/O**: `async.fetch()` and `async.sleep()`.
- **Run Lua code later, in its own coroutine**: `async.task()` and `async.run()`.
- **Coordinate**: `async.promise()`, `async.all()`, `task:wait()`.
- **Compute in parallel**: `async.parallel()`, with `table.freeze` and the shared `async.map`, `async.counter`
  and `async.channel`.

Load it with:

```lua
local async = require "async"
```

## Choosing an API

| Goal | Use |
|---|---|
| Wait for an HTTP response | `async.fetch(url)` |
| Wait for a number of ticks | `async.sleep(ticks)` |
| Start work and keep going | `async.task(fn)` |
| Run work and wait for its result | `async.run(fn)` |
| Wait for several tasks | `async.all(...)` |
| Coordinate manually-settled work | `async.promise()` |
| Run a heavy calculation off the server thread | `async.parallel(fn)` |

## `async.task(fn, ...)`

Starts a function in its own coroutine (on the next server task) and immediately returns a task object:

```lua
local task = async.task(function(x)
    async.sleep(20)
    return x * 2
end, 21)

local result = task:wait()
```

Arguments after `fn` are passed to it.

### Task methods and properties

#### `task:wait()`

Waits for completion and returns the raw result values. If the task fails, it throws the task error.

#### `task:wait(true)`

Protected wait: returns `true, result...` on success or `false, error` on failure and never throws because of the
task.

```lua
local ok, value = async.task(function()
    return risky_calculation()
end):wait(true)

if not ok then
    print("Calculation failed: " .. value)
end
```

#### `task.done`, `task.state`, `task.type`

`done` is `true` after the task resolved or rejected. `state` is `"pending"`, `"resolved"` or `"rejected"`.
`type` is `"task"` for tasks and `"promise"` for promises.

## `async.run(fn, ...)`

Runs `fn` as a task and waits for it. All return values are preserved and errors are thrown in the calling
coroutine. It must be called from a coroutine because it yields while waiting.

```lua
local total = async.run(function(a, b)
    async.sleep(5)
    return a + b
end, 10, 20)
```

## `async.all(...)`

Waits for every task or promise. It accepts varargs or an array-like table:

```lua
local results = async.all(task1, task2)
-- or:
local results = async.all { task1, task2 }
```

It throws the first rejection (in list order) after all inputs have settled. On success it returns an array of
entries with the **first** returned value of each task:

```lua
{ { ok = true, value = first_value }, { ok = true, value = second_value } }
```

`async.all(list, { settled = true })` never throws: a failed entry is `{ ok = false, error = "message" }`.

```lua
local results = async.all({ task1, task2 }, { settled = true })

for i, result in ipairs(results) do
    if result.ok then
        print(i, result.value)
    else
        print(i, "failed: " .. result.error)
    end
end
```

The `settled` option is available with the table form only.

## Promises

`async.promise()` creates an awaitable that you settle manually:

```lua
local promise = async.promise()

mc.schedule(20, function()
    promise:resolve("finished")
end)

print(promise:wait())
```

`promise:resolve(...)` and `promise:error(message)` return `true` if this was the first settlement; the first
settlement wins. Promises also expose `done`, `state` and `wait()`.

## `async.sleep(ticks)`

Suspends the current coroutine for a number of server ticks. Twenty ticks is approximately one second.

```lua
async.sleep(40)
print("Two seconds passed")
```

The coroutine resumes automatically; do not call `coroutine.resume()` yourself.

## `async.fetch(url)`

Sends a GET request and suspends the current coroutine until the response arrives. The request itself runs off the
server thread; your code resumes on it.

```lua
local response = async.fetch("https://api.example.com/data")

if response.ok then
    print(response.text)
else
    print("HTTP request failed: " .. (response.error or "unknown error"))
end
```

### `async.fetch(options)`

```lua
local response = async.fetch {
    url = "https://api.example.com/data",
    method = "POST",
    headers = {
        Authorization = "Bearer token"
    },
    json = { key = "value" },
    timeout = 10
}
```

| Option | Type | Default | Description |
|---|---|---:|---|
| `url` | string | required | Request URL; must have a scheme and host (`https://…`) |
| `method` | string | `"GET"` | HTTP method |
| `headers` | table | `{}` | Request headers |
| `body` | string | `nil` | Raw request body |
| `json` | table | `nil` | JSON request body; sets `Content-Type` when absent |
| `timeout` | number | `10` | Timeout in seconds, must be positive |

`body` and `json` are mutually exclusive. A URL without a scheme or a non-positive timeout is an error.

### Response fields

| Field | Type | Description |
|---|---|---|
| `ok` | boolean | `true` for HTTP status codes in the 2xx range |
| `status` | number or nil | HTTP status code when a response was received |
| `text` | string or nil | Response body |
| `headers` | table or nil | Response headers |
| `json` | table or nil | Lazily parsed JSON body (same mapping as [Storage](/reference/storage)) |
| `error` | string or nil | Network or request error |

HTTP errors such as 404 are returned as responses with `ok = false`. Transport failures also return `ok = false`,
but may not have a status code.

## Parallel functions

`async.parallel(fn)` moves a heavy calculation off the server thread. It returns a function; calling it runs `fn`
on a pool thread and suspends the calling coroutine until the result is back on the server thread:

```lua
local sum = async.parallel(function(n)
    local s = 0
    for i = 1, n do s = s + i end
    return s
end)

print(sum(100000000))   -- the server keeps ticking meanwhile
```

The wrapper yields like `async.run`, so it needs an async-capable coroutine (a command, a task, an event handler),
and an error in `fn` is raised in the caller (`pcall(sum, 10)` works).

### What a worker has

Each pool thread has its own Lua state with the standard library only: `string`, `table`, `math`, `bit32`,
`coroutine`, `require`, `vec` and the like. `mc`, `register`, events and storage do not exist there, and
neither does most of `async`: a worker has `async.map`, `async.counter` and `async.channel`.

- A global that is not part of the standard library fails when `async.parallel` is called: `global 'x' is not
  available in a parallel function (file:line)`. The same goes for assigning to any global.
- A global read through `_G[name]` cannot be seen up front; it fails inside the worker with the same message.
- Locals are fine. A captured local, a local function (recursive ones too) and a captured standard-library function
  such as `math.floor` all work.
- The pool has `min(4, cores - 1)` threads (at least one) and queues up to 256 calls; one more fails with
  `too many pending calls`. `/ignis reload` cancels running and queued calls.

### What crosses threads

Nothing mutable is shared implicitly. Captured values and arguments are **copied on every call**, so changing a
captured local between two calls is seen by the second one. Results are copied back.

| Value | Crosses as |
|---|---|
| `nil`, booleans, numbers, strings | itself |
| plain table (no metatable) | a copy; shared references and cycles inside it are kept |
| frozen table (`table.freeze`) | the same table, not copied |
| Lua function (the function, its captures, arguments) | a copy with its upvalues; results cannot be functions |
| standard-library function or table | the worker's own `math.floor`, `table`, ... |
| `vec` | a copy |
| `async.map` / `async.counter` / `async.channel` | the same object |
| anything else (other userdata, Java functions, tables with a metatable, coroutines) | an error |

The error names the value: `async.parallel: upvalue 'p' is a userdata and cannot cross threads`,
`parallel call: argument #2.items[3] is a table with a metatable and cannot cross threads`,
`parallel function: result #1 is a function and cannot cross threads`. Captures are checked when `async.parallel`
is called, arguments when the wrapper is called.

### `table.freeze(t)` and `table.isfrozen(t)`

`table.freeze(t)` makes `t` and every table reachable from it read-only and returns `t`. Writing to a frozen table
(`t.x = 1`, `rawset`, `table.insert`, `table.sort`, `setmetatable`) raises `attempt to modify a frozen table`.
A frozen table is shared with workers by reference, which is why it is the way to hand a large read-only structure
(a grid, a lookup table) to a parallel function without copying it on every call:

```lua
local grid = table.freeze(build_grid())
local count = async.parallel(function() return #grid end)
```

Only nil, booleans, numbers, strings and other tables may be reachable from a frozen table; a
function, a table with a metatable or a userdata (shared objects included) fails with
`table.freeze: table.items[2] is a function and cannot be frozen`, and nothing is frozen in that case.

### Shared objects

These are the only mutable values that main and workers share. All of them are thread-safe.

| Constructor | Methods |
|---|---|
| `async.map()` | `get(k)`, `set(k, v)` (`nil` removes), `remove(k)`, `update(k, fn)`, `size()`, `keys()` |
| `async.counter([n])` | `get()`, `set(n)`, `add([n])`, `compareAndSet(expected, new)` |
| `async.channel([capacity])` | `send(v)`, `receive()`, `close()` |

Map keys are strings, numbers or booleans. Values stored in a map or sent through a channel are booleans, numbers,
strings, frozen tables or shared objects; anything else is an error.

`m:update(k, fn)` replaces the value of `k` by `fn(old)` atomically (`fn` may run more than once when another thread
wins the race, so keep it free of side effects) and returns the new value:

```lua
local hits = async.map()
local count = async.parallel(function(name)
    return hits:update(name, function(n) return (n or 0) + 1 end)
end)
```

`c:add(n)` returns the new value; `n` defaults to 1. A counter holds an integer.

A channel is a bounded FIFO (capacity 16 unless given, at least 1). On the server thread `ch:send(v)` yields the
coroutine while the channel is full and `ch:receive()` yields while it is empty; in a worker they block the thread.
`ch:receive()` returns `nil` once the channel is closed and drained; `send` on a closed channel raises
`channel is closed`; `close()` can be called more than once.

```lua
local ch = async.channel(4)
async.task(function()
    async.parallel(function()
        for i = 1, 100 do ch:send(i) end
        ch:close()
    end)()
end)

while true do
    local v = ch:receive()
    if v == nil then break end
    print(v)
end
```

## Script reload

`/ignis reload` cancels everything that is still pending: tasks that have not started, waiters, sleeping coroutines,
`fetch` requests that finish afterwards, and parallel calls (running workers are interrupted, queued calls are
dropped). None of them resumes in the new script state.

## Critical sections

Code between two yield points is atomic, because only one coroutine runs at a time. A section
that yields (`sleep`, `fetch`, `wait`) can be serialised with a chain of promises. Run the section as a task and
wait for it with a protected wait, so that an error still releases the lock:

```lua
local tail = async.promise(); tail:resolve()

local function with_lock(fn)
    local previous, mine = tail, async.promise()
    tail = mine
    previous:wait()
    local ok, err = async.task(fn):wait(true)
    mine:resolve()
    if not ok then error(err, 0) end
end
```

## Errors across a yield

`pcall(function() async.sleep(5) end)` is an error: the call raises `attempt to yield across a C-call boundary`,
`pcall` returns `false, "…C-call boundary"` and the body does not continue past the yield.
Use `async.task(fn):wait(true)` (or `async.run`) when you need a protected section that waits.

## Coroutine requirements

`async.sleep()`, `async.fetch()`, `task:wait()`, `async.run()`, `async.all()`, calls of an `async.parallel`
function and `ch:send()` / `ch:receive()` on a channel may yield. They require a
coroutine-backed execution context. Commands, scheduled callbacks, tasks, and coroutine-backed event handlers can
use them. Do not manually resume a coroutine suspended by an `async` operation.

A yield cannot cross a Java library function: `table.sort` and `string.gsub` callbacks must not call `async`
functions (`attempt to yield across a C-call boundary`).
