---
title: Async API
description: Coroutine-based HTTP requests and tick delays with mc.fetch and mc.sleep.
---

Coroutine-based async operations. Under the hood, `mc.fetch` and `mc.sleep`
yield the execution and resume when the operation completes.

## mc.sleep(ticks)

Yields the current coroutine and resumes after the specified number of ticks (20 ticks = 1 second).

```lua
mc.broadcast("Wait for 2 seconds...")
mc.sleep(40)
mc.broadcast("Done!")
```

## mc.fetch(url)

Simple GET request. Returns a response table.

```lua
local res = mc.fetch("https://api.example.com/data")

if res.ok then
    mc.broadcast(res.text)
else
    mc.broadcast("Error: " .. res.error)
end
```

## mc.fetch {...}

Full request with options:

| Option    | Type   | Default | Description                                                    |
|-----------|--------|---------|----------------------------------------------------------------|
| `url`     | string | —       | Request URL (required)                                         |
| `method`  | string | `"GET"` | HTTP method                                                    |
| `headers` | table  | `{}`    | Custom headers                                                 |
| `body`    | string | `nil`   | Raw request body                                               |
| `json`    | table  | `nil`   | Auto-encodes to JSON and sets `Content-Type: application/json` |
| `timeout` | number | `10`    | Timeout in seconds       |

`body` & `json` are obviously mutually exclusive.

```lua
local res = mc.fetch {
    url = "https://api.example.com/data",
    method = "POST",
    json = { key = "value" },
    headers = { Authorization = "Bearer token" },
    timeout = 10
}
```

## Response Table

| Field         | Type          | Description                               |
|---------------|---------------|-------------------------------------------|
| `res.ok`      | boolean       | `true` if status is 2xx                   |
| `res.status`  | number        | HTTP status code                          |
| `res.text`    | string        | Response body as string                   |
| `res.headers` | table         | Response headers                          |
| `res.json`    | table or nil  | Lazy-parsed JSON (parsed on first access) |
| `res.error`   | string or nil | Error message if the request failed       |

```lua
local res = mc.fetch("https://api.github.com/repos/user/repo")
if res.ok then
    local data = res.json
    mc.broadcast("Stars: " .. data.stargazers_count)
end
```

## Example

```lua
register("fetch", function(ctx)
    -- Step 1: fetch a post
    local post = mc.fetch("https://jsonplaceholder.typicode.com/posts/1")
    local postData = post.json
    
    -- Step 2: wait a tick
    mc.sleep(1)
    
    -- Step 3: fetch comments
    local comments = mc.fetch("https://jsonplaceholder.typicode.com/posts/" .. postData.id .. "/comments")
    
    ctx.player:sendMessage("Fetched " .. #comments.json .. " comments")
end)
```

## Where does it work?

`mc.sleep` and `mc.fetch` only work inside **coroutines**, i.e. **command handlers** (`register(...)`) and
**scheduled callbacks** (`mc.schedule`, `mc.scheduleRepeating`). They do **not** work inside
event handlers (`mc.on(...)`), which run on the main thread and cannot be yielded.

If you need async behaviour in an event, use these:
```lua
mc.schedule(0, function()
    p:sendMessage("Called in the next tick")
    mc.sleep(20) -- yay
end)

-- OR

coroutine.wrap(function()
    p:sendMessage("Called immediately")
    mc.sleep(20)
end)()
```

**Important**: you **MUST NOT** resume coroutines that were yielded by these async functions. They shall be resumed
automatically.

## Lifecycle

All pending coroutines (sleeps, in-flight HTTP requests) are **discarded** on `/ignis reload`.

Don't use `mc.sleep` for critical mechanics (e.g. ban durations or daily bonuses). For long pauses,
save timestamps in [persistent storage](/reference/storage).

`mc.sleep` is ideal for short delays: animations, spell casting, etc.