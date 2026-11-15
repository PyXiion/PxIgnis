-- core:ref: local reactive values. `ref.new(v)` holds one value; `watch` runs after real changes. `ref.effect` and
-- `ref.computed` track every ref read through `get()` while they run and re-run when one of them changes.
--
-- Anything with `:watch(fn)` (returning a disconnect function) that calls `ref.track(self)` from its read method
-- takes part in tracking, so other reactive containers can plug in without changes here.
local ref = {}

local Ref = {}
Ref.__index = Ref
Ref.__name = "ref"

local MAX_RERUNS = 100

-- The effect currently running (nil outside effects and while watchers run).
local active = nil

local function report(what, err)
    if mc and mc.warn then
        mc.warn("ref", what .. ": " .. tostring(err))
    else
        print("ref " .. what .. ": " .. tostring(err))
    end
end

---Registers `r` as a dependency of the running effect. Reactive containers call this from their read methods.
function ref.track(r)
    if active then active.deps[r] = true end
end

function ref.isRef(x)
    return getmetatable(x) == Ref
end

function ref.new(initial)
    return setmetatable({ value = initial, watchers = {}, readonly = false }, Ref)
end

---The current value; counts as a dependency of the running effect.
function Ref:get()
    ref.track(self)
    return self.value
end

---The current value without tracking.
function Ref:peek()
    return self.value
end

local function notify(self, new, old)
    local saved = active
    active = nil
    local list = {}
    for i, w in ipairs(self.watchers) do list[i] = w end
    for _, w in ipairs(list) do
        if w.live then
            local ok, err = pcall(w.fn, new, old)
            if not ok then report("watcher failed", err) end
        end
    end
    active = saved
end

-- Replaces the value and notifies; the one write path (computed refs use it too).
local function assign(self, v)
    local old = self.value
    if rawequal(v, old) then return end
    self.value = v
    notify(self, v, old)
end

---Stops a computed ref from recomputing (no-op on a plain ref).
function Ref:dispose()
    if self.stop then self.stop() end
end

function Ref:set(v)
    if self.readonly then error("ref: a computed ref is read-only", 2) end
    assign(self, v)
end

---Sets the value to `fn(old)`. If `fn` fails the ref is unchanged.
function Ref:update(fn)
    if self.readonly then error("ref: a computed ref is read-only", 2) end
    if type(fn) ~= "function" then error("ref.update: a function is expected", 2) end
    assign(self, fn(self.value))
end

---Calls `fn(new, old)` after every change (not on registration). Returns a function that disconnects it; safe to call
---repeatedly and from inside a notification.
function Ref:watch(fn)
    if type(fn) ~= "function" then error("ref.watch: a function is expected", 2) end
    local w = { fn = fn, live = true }
    local watchers = self.watchers
    watchers[#watchers + 1] = w
    return function()
        if not w.live then return end
        w.live = false
        for i, x in ipairs(watchers) do
            if x == w then table.remove(watchers, i) break end
        end
    end
end

---Runs `fn` now and again whenever a ref it read changes. The dependencies are collected afresh on each run.
---Effects created inside `fn` belong to that run and are disposed before the next one. Returns `dispose`.
function ref.effect(fn)
    if type(fn) ~= "function" then error("ref.effect: a function is expected", 2) end
    local parent = active
    local e = { deps = {}, subs = {}, children = {}, live = true, running = false, pending = false }

    local function clear()
        for _, off in ipairs(e.subs) do off() end
        e.subs = {}
        for _, child in ipairs(e.children) do child() end
        e.children = {}
    end

    local run
    local function schedule()
        if not e.live then return end
        if e.running then e.pending = true return end
        run()
    end

    function run()
        local reruns = 0
        repeat
            e.pending = false
            clear()
            e.deps = {}
            e.running = true
            local saved = active
            active = e
            local ok, err = pcall(fn)
            active = saved
            e.running = false
            if not ok then report("effect failed", err) end
            if e.live then
                for dep in pairs(e.deps) do e.subs[#e.subs + 1] = dep:watch(schedule) end
            end
            reruns = reruns + 1
            if e.pending and reruns >= MAX_RERUNS then
                report("effect failed", "re-run limit reached, it keeps changing its own dependencies")
                break
            end
        until not (e.pending and e.live)
    end

    local function dispose()
        if not e.live then return end
        e.live = false
        clear()
    end

    if parent then parent.children[#parent.children + 1] = dispose end
    run()
    return dispose
end

---A read-only ref holding `fn()`, recomputed when the refs it reads change. `r:dispose()` stops recomputing.
---Reading it inside an effect makes that effect depend on it.
function ref.computed(fn)
    if type(fn) ~= "function" then error("ref.computed: a function is expected", 2) end
    local r = ref.new(nil)
    r.stop = ref.effect(function() assign(r, fn()) end)
    r.readonly = true
    return r
end

return ref
