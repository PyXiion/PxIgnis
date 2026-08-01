---
title: Async API
description: Non-blocking I/O, parallel Lua work, tasks, promises, and coroutine synchronization.
---

`async` has two different jobs:

- **Wait for I/O** without blocking the server or a worker: `async.fetch()` and `async.sleep()`.
- **Run Lua code on a selected executor**: `async.task()` and `async.run()`.

Load it with:

```lua
local async = require "async"
```

## Choosing an API

| Goal | Use |
|---|---|
| Wait for an HTTP response | `async.fetch(url)` |
| Wait for a number of ticks | `async.sleep(ticks)` |
| Run expensive Lua code in parallel | `async.task("threadpool", fn)` |
| Run Lua code on the Minecraft server thread | `async.task("main", fn)` |
| Run work and wait for its result | `async.run(executor, fn)` |
| Coordinate manually-settled work | `async.promise()` |
| Protect a shared Lua section | `async.mutex()` |

Do not put `async.fetch()` in a thread-pool task just to make the request asynchronous. HTTP requests are already non-blocking:

```lua
local response = async.fetch("https://example.com/data")
```

Use the thread pool for CPU-heavy Lua work.

## Executors

Every task requires an executor:

| Executor | Intended use |
|---|---|
| `"main"` | Minecraft APIs, entities, worlds, players, inventories, and other server state |
| `"threadpool"` | Expensive pure-Lua calculations and independent background work |

`"main"` runs Lua on the Minecraft server thread. Keep it short. A long calculation there pauses the server tick.

`"threadpool"` runs Lua away from the server thread. Do not access Minecraft objects from it:

```lua
-- Safe: pure computation
local task = async.task("threadpool", function()
    return generate_mesh(input)
end)

-- Unsafe: Minecraft state belongs on the main executor
async.task("threadpool", function()
    player:sendMessage("hello")
end)
```

## `async.run(executor, fn, ...)`

Runs a function on the selected executor and waits for its result. All return values are preserved, and errors are thrown in the calling coroutine.

```lua
local total = async.run("threadpool", function(a, b)
    return expensive_calculation(a, b)
end, 10, 20)
```

Use `"main"` when the function must interact with Minecraft:

```lua
async.run("main", function()
    player:sendMessage("The calculation is complete")
end)
```

`async.run()` must be called from a coroutine because it may yield while waiting.

## `async.task(executor, fn, ...)`

Starts a function and immediately returns a task object:

```lua
local task = async.task("threadpool", function()
    return expensive_calculation()
end)

local result = task:wait()
```

The function receives the arguments after `fn`:

```lua
local task = async.task("threadpool", function(x)
    return x * 2
end, 21)
```

### Task methods and properties

#### `task:wait()`

Waits for completion and returns the raw result values. If the task fails, it throws the task error.

```lua
local value = async.task("threadpool", function()
    return 42
end):wait()
```

#### `task:try()`

Waits for completion and returns `true, result...` on success or `false, error` on failure.

```lua
local ok, value = async.task("threadpool", function()
    return risky_calculation()
end):try()

if not ok then
    print("Calculation failed: " .. value)
end
```

#### `task.done`

`true` after the task has resolved or rejected.

#### `task.state`

One of:

- `"pending"`
- `"resolved"`
- `"rejected"`

#### `task.type`

Returns `"task"` for task objects and `"promise"` for promise objects.

Tasks can call other coroutine-aware operations while running:

```lua
local task = async.task("threadpool", function()
    async.sleep(20)
    local response = async.fetch("https://example.com/data")
    return response.json
end)
```

## Parallel work

Start independent calculations first, then wait for all of them:

```lua
local left = async.task("threadpool", function()
    return generate_chunk(1)
end)

local right = async.task("threadpool", function()
    return generate_chunk(2)
end)

local results = async.all(left, right)
local left_chunk = results[1].value
local right_chunk = results[2].value
```

The calculations can run concurrently. Apply the resulting data through the main executor if it touches Minecraft:

```lua
async.run("main", function()
    apply_chunk(left_chunk)
    apply_chunk(right_chunk)
end)
```

## `async.all(...)`

Waits for every task or promise. It accepts either varargs or an array-like table:

```lua
local results = async.all(task1, task2)
-- or:
local results = async.all { task1, task2 }
```

It throws the first rejection after all inputs have settled. On success, it returns an array of result entries:

```lua
{
    { ok = true, value = first_value },
    { ok = true, value = second_value }
}
```

The result entry currently stores the first returned value. Use individual `task:wait()` calls when you need multiple return values from each task.

## `async.allSettled(...)`

Waits for every input and never throws because of a rejected task:

```lua
local results = async.allSettled(task1, task2)

for i, result in ipairs(results) do
    if result.ok then
        print(i, result.value)
    else
        print(i, "failed: " .. result.error)
    end
end
```

Each entry has either:

```lua
{ ok = true, value = value }
```

or:

```lua
{ ok = false, error = "error message" }
```

## Promises

`async.promise()` creates an awaitable that you settle manually:

```lua
local promise = async.promise()

mc.schedule(20, function()
    promise:resolve("finished")
end)

print(promise:wait())
```

### `promise:resolve(...)`

Resolves the promise and returns `true` if this was the first settlement.

### `promise:error(message)`

Rejects the promise and returns `true` if this was the first settlement.

The first settlement wins:

```lua
local promise = async.promise()

print(promise:resolve(1)) -- true
print(promise:resolve(2)) -- false
```

Promises also expose `done`, `state`, `wait()`, and `try()`.

## `async.sleep(ticks)`

Suspends the current coroutine for a number of server ticks. Twenty ticks is approximately one second.

```lua
async.sleep(40)
print("Two seconds passed")
```

It does not block the executor thread. The coroutine resumes automatically; do not call `coroutine.resume()` yourself.

## `async.fetch(url)`

Sends a GET request and suspends the current coroutine until the response arrives:

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
| `url` | string | required | Request URL |
| `method` | string | `"GET"` | HTTP method |
| `headers` | table | `{}` | Request headers |
| `body` | string | `nil` | Raw request body |
| `json` | table | `nil` | JSON request body; sets `Content-Type` when absent |
| `timeout` | number | `10` | Timeout in seconds |

`body` and `json` are mutually exclusive.

### Response fields

| Field | Type | Description |
|---|---|---|
| `ok` | boolean | `true` for HTTP status codes in the 2xx range |
| `status` | number or nil | HTTP status code when a response was received |
| `text` | string or nil | Response body |
| `headers` | table or nil | Response headers |
| `json` | table or nil | Lazily parsed JSON body |
| `error` | string or nil | Network or request error |

HTTP errors such as 404 are returned as responses with `ok = false`. Transport failures also return `ok = false`, but may not have a status code.

## Mutexes

`async.mutex()` creates a coroutine-friendly mutex:

```lua
--# nova syntax

local mutex = async.mutex()

local value = mutex:with \{
    return update_shared_cache()
}
```

Only one callback owns the mutex at a time. Waiting callbacks yield instead of blocking a Java thread. The callback may use `async.sleep()`, `async.fetch()`, or `task:wait()`.

The mutex is released when the callback returns or throws:

```lua
--# nova syntax

local ok, err = pcall \{
    mutex:with \{
        error("the lock is still released")
    }
}
```

Mutexes are non-reentrant. Avoid waiting for another mutex while holding one, because that can deadlock.

## Coroutine requirements

`async.sleep()`, `async.fetch()`, `task:wait()`, `task:try()`, `async.run()`, `async.all()`, `async.allSettled()`, and `mutex:with()` may yield. They require a coroutine-backed execution context.

Commands, scheduled callbacks, tasks, and coroutine-backed event handlers can use these APIs. The runtime resumes suspended coroutines automatically.

Do not manually resume a coroutine suspended by an `async` operation.

## Minecraft API boundary

Keep Minecraft work on the main executor and calculation work on the thread pool:

```lua
local generated = async.run("threadpool", function()
    return generate_data()
end)

async.run("main", function()
    place_generated_data(generated)
end)
```

Do not pass live Minecraft objects into thread-pool code and access them there. Extract plain data on the main executor first, then pass the data to the calculation.
