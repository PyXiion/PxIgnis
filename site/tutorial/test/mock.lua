-- A small fake of the PxIgnis API, just enough to run the tutorial scripts in plain Lua 5.2 and check
-- the game logic. It is not a model of the real server: behaviour the tutorial relies on is imitated
-- (storage copies tables on assignment, cancelled events stop later handlers, regions fire enter/leave).
local M = { log = {}, emitted = {}, tickNo = 0 }

local function log(s) M.log[#M.log + 1] = s end

-- ── persistent data: assignment copies tables (like DataTable) ─────────────
local function persist(src)
    local store = {}
    local proxy = setmetatable({}, {
        __index = store,
        __newindex = function(_, k, v)
            assert(type(k) == "string" or type(k) == "number", "bad key " .. tostring(k))
            local t = type(v)
            assert(t == "nil" or t == "string" or t == "number" or t == "boolean" or t == "table",
                "Cannot store " .. t .. " values in persistent data")
            if t == "table" then
                if getmetatable(v) and getmetatable(v).__persist then
                    store[k] = v
                else
                    assert(not getmetatable(v), "cannot store table with metatable (userdata-like)")
                    store[k] = M.persist(v)
                end
            else
                store[k] = v
            end
        end,
        __len = function() return #store end,
        __pairs = function() return next, store, nil end,
        __persist = true,
    })
    for k, v in pairs(src or {}) do proxy[k] = v end
    return proxy
end
M.persist = persist

-- JSON-like round trip: sequences stay sequences, other keys become strings.
function M.roundtrip(p)
    local function copy(t)
        local out, n = {}, #t
        local isList = n > 0
        for k in pairs(t) do
            if type(k) ~= "number" or k < 1 or k > n then isList = false end
        end
        for k, v in pairs(t) do
            local key = isList and k or tostring(k)
            out[key] = type(v) == "table" and copy(v) or v
        end
        return out
    end
    return persist(copy(p))
end

-- ── vec ───────────────────────────────────────────────────────────────────
local V = {}
V.__index = V
local function vec(x, y, z) return setmetatable({ x = x, y = y, z = z }, V) end
V.__add = function(a, b)
    if type(b) == "number" then return vec(a.x + b, a.y + b, a.z + b) end
    return vec(a.x + b.x, a.y + b.y, a.z + b.z)
end
V.__tostring = function(a) return string.format("(%g, %g, %g)", a.x, a.y, a.z) end
_G.vec = vec

-- ── world / regions / holograms ───────────────────────────────────────────
local worlds = {}
M.regions = {}
local function world(name)
    if worlds[name] then return worlds[name] end
    local w = { name = name }
    function w:particle(id, pos, opts) assert(getmetatable(pos) == V) log("particle " .. id) end
    function w:playSound(id, pos) log("sound " .. id) end
    function w:spawnHologram(pos, text)
        local h = { text = text, lines = { text }, pos = pos, destroyed = false }
        function h:destroy() self.destroyed = true end
        M.hologram = h
        return h
    end
    function w:createRegion(a, b)
        local r = { world = w, a = a, b = b, handlers = {}, inside = {}, destroyed = false }
        function r:on(ev, fn) self.handlers[ev] = self.handlers[ev] or {}; table.insert(self.handlers[ev], fn); return 1 end
        function r:contains(p)
            return p.x >= a.x and p.x < b.x and p.y >= a.y and p.y < b.y and p.z >= a.z and p.z < b.z
        end
        function r:destroy() self.destroyed = true end
        table.insert(M.regions, r)
        return r
    end
    worlds[name] = w
    return w
end

-- ── players ───────────────────────────────────────────────────────────────
M.players = {}
function M.player(name, opts)
    local p = {
        uuid = "uuid-" .. name, name = name, pos = vec(0, 64, 0), world = world("overworld"),
        health = 20, maxHealth = 20, food = 20, fireTicks = 0, inv = {}, msgs = {}, titles = {},
        perms = opts and opts.perms or {}, online = true,
    }
    p.data = persist({})
    function p:sendMessage(t) table.insert(self.msgs, t); log(self.name .. " <- " .. t) end
    function p:sendTitle(a, b) table.insert(self.titles, a .. " " .. (b or "")) end
    function p:playSound() end
    function p:hasPermission(perm) return self.perms[perm] == true end
    function p:teleport(x, y, z, w)
        assert(type(x) == "number" and type(y) == "number" and type(z) == "number", "teleport: numbers expected")
        assert(w == nil or type(w) == "string", "teleport: world name")
        self.pos = vec(x, y, z); self.world = world(w or self.world.name)
    end
    function p:give(id, count)
        assert(type(id) == "string")
        for slot = 0, 35 do
            if not self.inv[slot] then self.inv[slot] = { id = id, count = count or 1 }; return end
        end
    end
    function p:has(id)
        for _, it in pairs(self.inv) do if it.id == id then return it end end
    end
    function p:getItem(slot)
        assert(slot >= 0 and slot <= 40)
        return self.inv[slot] and M.item(self.inv[slot].id) or nil
    end
    function p:setItem(slot, item) assert(type(slot) == "number"); self.inv[slot] = item and { id = item.id } or nil end
    function p:clear() self.inv = {}; self.chestItem = nil end
    setmetatable(p, {
        __newindex = function(t, k, v)
            if k == "chest" then rawset(t, "chestItem", v); return end
            if k == "sidebar" then
                assert(v == nil or type(v) == "table")
                rawset(t, "sidebarValue", v); return
            end
            rawset(t, k, v)
        end,
    })
    table.insert(M.players, p)
    return p
end

function M.item(id)
    local it = { id = id }
    function it:serialise() return "json:" .. self.id end
    return it
end

-- ── events ────────────────────────────────────────────────────────────────
local handlers = {}
local PRI = { lowest = -200, low = -100, normal = 0, high = 100, highest = 200 }
local mc = {}
function mc.on(name, fn, opts)
    local pr = opts and opts.priority or 0
    if type(pr) == "string" then pr = assert(PRI[pr]) end
    handlers[name] = handlers[name] or {}
    table.insert(handlers[name], { fn = fn, pr = pr })
    table.sort(handlers[name], function(a, b) return a.pr > b.pr end)
end
function M.fire(name, fields, cancellable)
    local e = fields or {}
    e.name = name; e.cancelled = false
    function e:cancel() assert(cancellable, "not cancellable " .. name); self.cancelled = true end
    for _, h in ipairs(handlers[name] or {}) do
        if e.cancelled then break end
        local co = coroutine.create(h.fn)
        local ok, err = coroutine.resume(co, e)
        if not ok then error("handler of " .. name .. ": " .. tostring(err)) end
    end
    return e
end
function mc.emit(name, ...)
    table.insert(M.emitted, { name, ... })
    for _, h in ipairs(handlers[name] or {}) do
        local co = coroutine.create(h.fn)
        local ok, err = coroutine.resume(co, ...)
        if not ok then error("handler of " .. name .. ": " .. tostring(err)) end
    end
    return true
end
function mc.broadcast(t) log("broadcast " .. t) end

-- ── scheduler + async ─────────────────────────────────────────────────────
local tasks = {}
function mc.schedule(delay, fn) table.insert(tasks, { at = M.tickNo + math.max(delay, 0), fn = fn }) end
function mc.scheduleRepeating(delay, interval, fn) table.insert(tasks, { at = M.tickNo + delay, every = interval, fn = fn }) end
local async = {}
function async.sleep(ticks)
    local co = assert(coroutine.running(), "async.sleep outside coroutine")
    table.insert(tasks, { at = M.tickNo + ticks, co = co })
    coroutine.yield()
end
local function runTask(t)
    local co = t.co or coroutine.create(t.fn)
    local ok, err = coroutine.resume(co)
    if not ok then error("task: " .. tostring(err)) end
end
function M.regionTick()
    for _, r in ipairs(M.regions) do
        if not r.destroyed then
            for _, p in ipairs(M.players) do
                if p.online then
                    local now = p.world == r.world and r:contains(p.pos)
                    local was = r.inside[p.uuid] or false
                    if now ~= was then
                        r.inside[p.uuid] = now
                        for _, fn in ipairs(r.handlers[now and "enter" or "leave"] or {}) do
                            local co = coroutine.create(fn)
                            assert(coroutine.resume(co, { entity = p, player = p, region = r }))
                        end
                    end
                end
            end
        end
    end
end
function M.tick(n)
    for _ = 1, n or 1 do
        M.tickNo = M.tickNo + 1
        local due = {}
        for i = #tasks, 1, -1 do
            if tasks[i].at <= M.tickNo then table.insert(due, 1, table.remove(tasks, i)) end
        end
        for _, t in ipairs(due) do
            if t.every then t.at = M.tickNo + t.every; table.insert(tasks, t) end
            runTask(t)
        end
        M.regionTick()
    end
end
function M.seconds(n) M.tick(n * 20) end

-- ── misc API ──────────────────────────────────────────────────────────────
mc.data = persist({})
function mc.world(name) return world(name) end
function mc.createItem(id, opts) local it = M.item(id); it.opts = opts; return it end
function mc.deserialise(kind, json) assert(kind == "item"); return M.item(json:sub(6)) end
function mc.createBossBar(title)
    local b = { title = title, progress = 0, players = {} }
    function b:addPlayer(p) self.players[p.uuid] = true end
    function b:removePlayer(p) self.players[p.uuid] = nil end
    M.bar = b
    return b
end
M.commands = {}
function _G.register(syntax, fn, perm) M.commands[syntax] = { fn = fn, perm = perm } end
function M.run(player, syntax, ...)
    local c = assert(M.commands[syntax], "no command " .. syntax)
    if c.perm then assert(player:hasPermission(c.perm), "no permission") end
    local co = coroutine.create(c.fn)
    local ok, err = coroutine.resume(co, { player = player }, ...)
    if not ok then error(syntax .. ": " .. tostring(err)) end
end

local chestgui = {}
function chestgui.create(rows, title)
    local g = { rows = rows, title = title, cbs = {} }
    function g:set(r, c, item, cb) assert(c >= 1 and c <= 9); self.cbs[(r - 1) * 9 + c] = { item = item, cb = cb } end
    function g:open(p) M.openGui = self end
    function g:close(p) end
    function g:click(p, slot) local s = self.cbs[slot]; return s and s.cb and s.cb(p, slot, "pickup", s.item, nil) end
    return g
end

package.preload["async"] = function() return async end
package.preload["core:chestgui"] = function() return chestgui end
_G.mc = mc
M.mc = mc
function M.reset()
    handlers = {}; tasks = {}; M.regions = {}; M.commands = {}
end
return M
