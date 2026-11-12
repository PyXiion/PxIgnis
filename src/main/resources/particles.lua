-- core:particles — tick-driven particle effects.
--
-- An effect is a tree of plain-data nodes (seq, par, wait, times, loop, with, shapes, effect) played by `fx.play`.
-- Every node takes at least one server tick, and steps never yield, so a tree is a pure state machine:
-- `loop` cannot spin without advancing time, and the walker could move to Kotlin without changing this interface.
--
--   local fx = require "core:particles"
--   local spark = particle("end_rod")
--   local h = fx.play(fx.seq {
--       fx.burst { particle = spark, radius = 3 },
--       fx.wait(10),
--       fx.times(3, fx.circle { particle = spark, radius = 2 }),
--   }, { at = player })
--   h:stop()   -- or: h:wait() inside a coroutine
local async = require "async"

local fx = {}

local TAU = 2 * math.pi
local FIB_ANGLE = math.pi * (3 - math.sqrt(5))

-- ── Validation ─────────────────────────────────────────────────────────
-- Validators raise plain messages; `ctor` re-raises them at the caller of the public constructor.

local function fail(name, opt, what, v)
    error(("fx.%s: %s must be %s, got %s"):format(name, opt, what, tostring(v)), 0)
end

local function isInt(v, min)
    return type(v) == "number" and v == math.floor(v) and v >= min and v < math.huge
end

local function checkInt(name, opt, v, min)
    if not isInt(v, min) then fail(name, opt, "an integer >= " .. min, v) end
    return v
end

local function pickInt(name, o, key, default, min)
    local v = o[key]
    if v == nil then return default end
    return checkInt(name, key, v, min)
end

local function pickNum(name, o, key, default)
    local v = o[key]
    if v == nil then return default end
    if type(v) ~= "number" or v ~= v then fail(name, key, "a number", v) end
    return v
end

local function pickPos(name, o, key, default)
    local v = o[key]
    if v == nil then return default end
    if type(v) ~= "number" or not (v > 0) then fail(name, key, "a number > 0", v) end
    return v
end

local function checkViewers(name, v)
    if v ~= nil and type(v) ~= "table" then fail(name, "viewers", "a Player or a list of Players", v) end
    return v
end

local function ctor(f)
    return function(...)
        local ok, res = pcall(f, ...)
        if not ok then error(res, 2) end
        return res
    end
end

local function checkNode(name, opt, n)
    if type(n) ~= "table" or type(n.kind) ~= "string" then fail(name, opt, "an effect node", n) end
    return n
end

local function checkList(name, list)
    if type(list) ~= "table" or #list < 1 then fail(name, "children", "a non-empty list of effect nodes", list) end
    for i = 1, #list do checkNode(name, "child #" .. i, list[i]) end
    return list
end

-- ── Context ────────────────────────────────────────────────────────────
-- Passed to leaves. Derived contexts (`with`) reach these methods through their __index chain.

local CTX = {}
CTX.__index = CTX

local function withViewers(self, opts)
    if self.viewers == nil or (opts ~= nil and opts.viewers ~= nil) then return opts end
    local o = {}
    if opts ~= nil then
        for k, v in pairs(opts) do o[k] = v end
    end
    o.viewers = self.viewers
    return o
end

function CTX:emit(particle, pos, opts)
    return self.world:emit(particle, pos, withViewers(self, opts))
end

function CTX:emitMany(particle, positions, opts)
    return self.world:emitMany(particle, positions, withViewers(self, opts))
end

-- ── Nodes ──────────────────────────────────────────────────────────────

fx.seq = ctor(function(list)
    return { kind = "seq", children = checkList("seq", list) }
end)

fx.par = ctor(function(list)
    return { kind = "par", children = checkList("par", list) }
end)

fx.wait = ctor(function(ticks)
    return { kind = "wait", ticks = checkInt("wait", "ticks", ticks, 1) }
end)

fx.times = ctor(function(n, node)
    return { kind = "times", n = checkInt("times", "n", n, 1), child = checkNode("times", "node", node) }
end)

fx.loop = ctor(function(node)
    return { kind = "loop", child = checkNode("loop", "node", node) }
end)

fx.with = ctor(function(opts, node)
    if type(opts) ~= "table" then fail("with", "opts", "a table", opts) end
    if opts.offset ~= nil and type(opts.offset) ~= "userdata" then fail("with", "offset", "a Vec", opts.offset) end
    checkViewers("with", opts.viewers)
    return { kind = "with", offset = opts.offset, viewers = opts.viewers, child = checkNode("with", "node", node) }
end)

--- `factory()` runs once per instantiation (at play, and at every `times`/`loop` restart) and returns
--- `step(ctx)`, which runs once per tick. State lives in the factory's closure, so each run starts fresh.
--- A step ends the effect by returning true; otherwise it runs until stopped. Neither may yield.
fx.effect = ctor(function(factory)
    if type(factory) ~= "function" then fail("effect", "factory", "a function", factory) end
    return { kind = "effect", factory = factory }
end)

--- The common case of `fx.effect`: `fn(ctx, k)` runs once per tick with k = 0, 1, 2, ... for this run.
--- Ends after `ticks` ticks, or runs until stopped when `ticks` is nil. Must not yield.
fx.ticking = ctor(function(fn, ticks)
    if type(fn) ~= "function" then fail("ticking", "fn", "a function", fn) end
    if ticks ~= nil then checkInt("ticking", "ticks", ticks, 1) end
    return fx.effect(function()
        local k = 0
        return function(ctx)
            fn(ctx, k)
            k = k + 1
            return ticks ~= nil and k >= ticks
        end
    end)
end)

-- ── Instantiation ──────────────────────────────────────────────────────
-- An instance is `function(ctx) -> finished`, called once per driver tick.

local instantiate
local instantiators = {}

function instantiators.effect(node)
    local step = node.factory()
    if type(step) ~= "function" then
        error(("fx.effect: factory must return a step function, got %s"):format(tostring(step)), 0)
    end
    return step
end

function instantiators.wait(node)
    local n = 0
    return function()
        n = n + 1
        return n >= node.ticks
    end
end

function instantiators.seq(node)
    local children = node.children
    local i = 1
    local cur = instantiate(children[1])
    return function(ctx)
        if cur(ctx) then
            i = i + 1
            if i > #children then return true end
            cur = instantiate(children[i])
        end
        return false
    end
end

function instantiators.par(node)
    local insts, done, left = {}, {}, #node.children
    for i, child in ipairs(node.children) do insts[i] = instantiate(child) end
    return function(ctx)
        for i = 1, #insts do
            if not done[i] and insts[i](ctx) then
                done[i] = true
                left = left - 1
            end
        end
        return left == 0
    end
end

function instantiators.times(node)
    local i = 1
    local cur = instantiate(node.child)
    return function(ctx)
        if cur(ctx) then
            i = i + 1
            if i > node.n then return true end
            cur = instantiate(node.child)
        end
        return false
    end
end

function instantiators.loop(node)
    local cur = instantiate(node.child)
    return function(ctx)
        if cur(ctx) then cur = instantiate(node.child) end
        return false
    end
end

function instantiators.with(node)
    local cur = instantiate(node.child)
    local derived
    local zero = vec(0, 0, 0)
    return function(ctx)
        if derived == nil then
            derived = setmetatable({ viewers = node.viewers }, { __index = ctx })
        end
        derived.origin = ctx.origin + (node.offset or zero)
        return cur(derived)
    end
end

instantiate = function(node)
    return instantiators[node.kind](node)
end

-- ── Shapes ─────────────────────────────────────────────────────────────

--- Common shape options: particle (required), duration, emit (passed through as ctx:emit opts).
local function common(name, o, defaultDuration)
    if type(o) ~= "table" then fail(name, "options", "a table", o) end
    if type(o.particle) ~= "userdata" then fail(name, "particle", "a particle", o.particle) end
    if o.emit ~= nil and type(o.emit) ~= "table" then fail(name, "emit", "a table", o.emit) end
    return o.particle, pickInt(name, o, "duration", defaultDuration, 1), o.emit
end

local function fibonacciSphere(origin, radius, points, phase)
    local out = {}
    for i = 0, points - 1 do
        local y = 1 - 2 * (i + 0.5) / points
        local r = math.sqrt(1 - y * y)
        local a = i * FIB_ANGLE + phase
        out[#out + 1] = origin + vec(math.cos(a) * r * radius, y * radius, math.sin(a) * r * radius)
    end
    return out
end

fx.circle = ctor(function(o)
    local particle, duration, emit = common("circle", o, 1)
    local radius = pickNum("circle", o, "radius", 1)
    local points = pickInt("circle", o, "points", 24, 1)
    local spin = pickNum("circle", o, "spin", 0)
    return fx.ticking(function(ctx, k)
        local out = {}
        for i = 0, points - 1 do
            local a = spin * k + i * TAU / points
            out[#out + 1] = ctx.origin + vec(math.cos(a) * radius, 0, math.sin(a) * radius)
        end
        ctx:emitMany(particle, out, emit)
    end, duration)
end)

fx.sphere = ctor(function(o)
    local particle, duration, emit = common("sphere", o, 1)
    local radius = pickNum("sphere", o, "radius", 1)
    local points = pickInt("sphere", o, "points", 48, 1)
    local spin = pickNum("sphere", o, "spin", 0)
    return fx.ticking(function(ctx, k)
        ctx:emitMany(particle, fibonacciSphere(ctx.origin, radius, points, spin * k), emit)
    end, duration)
end)

--- A shell that expands from the origin to `radius` over `duration` ticks.
fx.burst = ctor(function(o)
    local particle, duration, emit = common("burst", o, 12)
    local radius = pickNum("burst", o, "radius", 3)
    local points = pickInt("burst", o, "points", 60, 1)
    return fx.ticking(function(ctx, k)
        ctx:emitMany(particle, fibonacciSphere(ctx.origin, radius * (k + 1) / duration, points, 0), emit)
    end, duration)
end)

--- `to` is a Vec or an Entity (its position is re-read every tick).
fx.line = ctor(function(o)
    local particle, duration, emit = common("line", o, 1)
    local to = o.to
    if type(to) ~= "userdata" and not (type(to) == "table" and to.pos ~= nil) then
        fail("line", "to", "a Vec or an Entity", to)
    end
    local step = pickPos("line", o, "step", 0.25)
    return fx.ticking(function(ctx, k)
        local target = type(to) == "table" and to.pos or to
        local delta = target - ctx.origin
        local n = math.max(2, math.floor(delta:length() / step) + 1)
        local out = {}
        for i = 0, n - 1 do
            out[#out + 1] = ctx.origin + delta * (i / (n - 1))
        end
        ctx:emitMany(particle, out, emit)
    end, duration)
end)

fx.helix = ctor(function(o)
    local particle, duration, emit = common("helix", o, 40)
    local radius = pickNum("helix", o, "radius", 1)
    local height = pickNum("helix", o, "height", 2)
    local strands = pickInt("helix", o, "strands", 2, 1)
    local points = pickInt("helix", o, "points", 24, 2)
    local turns = pickNum("helix", o, "turns", 2)
    local spin = pickNum("helix", o, "spin", 0.25)
    return fx.ticking(function(ctx, k)
        local out = {}
        for s = 0, strands - 1 do
            for i = 0, points - 1 do
                local u = i / (points - 1)
                local a = spin * k + s * TAU / strands + u * turns * TAU
                out[#out + 1] = ctx.origin + vec(math.cos(a) * radius, u * height, math.sin(a) * radius)
            end
        end
        ctx:emitMany(particle, out, emit)
    end, duration)
end)

-- ── Playing ────────────────────────────────────────────────────────────

local Handle = {}
Handle.__index = Handle

--- Ends the effect. Returns false if it had already finished.
function Handle:stop()
    if self.done then return false end
    self._finish()
    return true
end

--- Suspends the calling coroutine until the effect finishes or is stopped.
function Handle:wait()
    local r = self._promise:wait()
    return r
end

--- Plays `node`. Options:
---   at       Vec, or an Entity/Player (required)
---   world    required with a Vec `at`; defaults to the entity's world
---   follow   track the entity every tick and stop when it is removed (entity `at` only)
---   viewers  Player or list of Players that see the particles (default: everyone in range)
fx.play = ctor(function(node, o)
    checkNode("play", "node", node)
    if type(o) ~= "table" then error("fx.play: options table required", 0) end

    local at = o.at
    local isVec = type(at) == "userdata"
    if not isVec and not (type(at) == "table" and at.pos ~= nil and at.world ~= nil) then
        error("fx.play: at must be a Vec or an Entity", 0)
    end
    local world = o.world
    if isVec then
        if world == nil then error("fx.play: world is required when at is a Vec", 0) end
        if o.follow then error("fx.play: follow requires an entity", 0) end
    else
        world = world or at.world
    end
    local follow = o.follow == true
    checkViewers("play", o.viewers)

    local ctx = setmetatable({ world = world, viewers = o.viewers }, CTX)

    local function place()
        if isVec then
            ctx.origin, ctx.forward, ctx.right = at, vec(0, 0, 1), vec(-1, 0, 0)
            return
        end
        local d = at.dir
        local len = math.sqrt(d.x * d.x + d.z * d.z)
        local forward = len < 1e-4 and vec(0, 0, 1) or vec(d.x / len, 0, d.z / len)
        ctx.origin, ctx.forward, ctx.right = at.pos, forward, vec(-forward.z, 0, forward.x)
    end
    place()

    local handle = setmetatable({ done = false }, Handle)
    local promise = async.promise()
    local taskId

    local function finish()
        handle.done = true
        mc.cancelTask(taskId)
        promise:resolve(true)
    end
    handle._finish = finish
    handle._promise = promise

    local inst = instantiate(node)
    taskId = mc.scheduleRepeating(0, 1, function()
        if handle.done then return end
        if follow then
            if at.removed then
                finish()
                return
            end
            place()
        end
        local ok, res = pcall(inst, ctx)
        if not ok then
            -- The scheduler logs the error but keeps repeating tasks alive, so stop explicitly first.
            finish()
            error(res, 0)
        end
        if res then finish() end
    end)

    return handle
end)

return fx
