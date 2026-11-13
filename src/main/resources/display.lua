-- core:display — conveniences over `world:display` (virtual, packet-only display entities).
--
--   local display = require "core:display"
--   local orb = display.item(world, pos, "minecraft:ender_pearl", { billboard = "center", scale = 0.4, glow = true })
--   orb:animate({ transform = { s = 0.8 } }, 10)
--   orb:life(100)
--
-- Handles returned here wrap the core handle (`h.h`): they forward set/moveTo/attach/detach/show/hide/remove/
-- onClick/onAttack and read `valid`, `pos`, `kind`, `id`, and add animate/spin/flyTo/fade/life. Transform tables are
-- `{t = {x,y,z}, l = quat, s = number | {x,y,z}, r = quat}` (translate, left rotation, scale, right rotation) or a
-- row-major 16-number matrix. Quaternions are `{x = , y = , z = , w = }` (a plain {x, y, z, w} array works as well).
local display = {}

local TAU = 2 * math.pi

-- ── Math ───────────────────────────────────────────────────────────────

local function comp(v, name, i, what)
    local x = v[name]
    if x == nil then x = v[i] end
    if type(x) ~= "number" then error(("display: %s must have numeric %s"):format(what or "vector", name), 3) end
    return x
end

local function vec3(v, what)
    if type(v) == "number" then return { x = v, y = v, z = v } end
    return { x = comp(v, "x", 1, what), y = comp(v, "y", 2, what), z = comp(v, "z", 3, what) }
end

local quat = {}

function quat.identity() return { x = 0, y = 0, z = 0, w = 1 } end

local function qget(q)
    if q == nil then return 0, 0, 0, 1 end
    return comp(q, "x", 1, "quaternion"), comp(q, "y", 2, "quaternion"), comp(q, "z", 3, "quaternion"), comp(q, "w", 4, "quaternion")
end

--- Rotation of `rad` radians about `axis` (any length, normalised here).
function quat.axis(axis, rad)
    local a = vec3(axis, "axis")
    local len = math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
    if len == 0 then error("display.quat.axis: axis must not be zero", 2) end
    local s = math.sin(rad / 2) / len
    return { x = a.x * s, y = a.y * s, z = a.z * s, w = math.cos(rad / 2) }
end

--- Hamilton product: `mul(a, b)` applies b first, then a.
function quat.mul(a, b)
    local ax, ay, az, aw = qget(a)
    local bx, by, bz, bw = qget(b)
    return {
        x = aw * bx + ax * bw + ay * bz - az * by,
        y = aw * by - ax * bz + ay * bw + az * bx,
        z = aw * bz + ax * by - ay * bx + az * bw,
        w = aw * bw - ax * bx - ay * by - az * bz,
    }
end

--- Yaw about Y, pitch about X, roll about Z (radians); applied roll first, then pitch, then yaw.
function quat.euler(yaw, pitch, roll)
    local qy = quat.axis({ 0, 1, 0 }, yaw or 0)
    local qx = quat.axis({ 1, 0, 0 }, pitch or 0)
    local qz = quat.axis({ 0, 0, 1 }, roll or 0)
    return quat.mul(qy, quat.mul(qx, qz))
end

function quat.slerp(a, b, t)
    local ax, ay, az, aw = qget(a)
    local bx, by, bz, bw = qget(b)
    local dot = ax * bx + ay * by + az * bz + aw * bw
    if dot < 0 then bx, by, bz, bw, dot = -bx, -by, -bz, -bw, -dot end
    local k0, k1
    if dot > 0.9995 then
        k0, k1 = 1 - t, t
    else
        local theta = math.acos(dot)
        local sin = math.sin(theta)
        k0, k1 = math.sin((1 - t) * theta) / sin, math.sin(t * theta) / sin
    end
    local x, y, z, w = ax * k0 + bx * k1, ay * k0 + by * k1, az * k0 + bz * k1, aw * k0 + bw * k1
    local n = math.sqrt(x * x + y * y + z * z + w * w)
    return { x = x / n, y = y / n, z = z / n, w = w / n }
end

local function quatMatrix(q)
    local x, y, z, w = qget(q)
    local n = x * x + y * y + z * z + w * w
    local s = n > 0 and 2 / n or 0
    local xx, yy, zz = x * x * s, y * y * s, z * z * s
    local xy, xz, yz = x * y * s, x * z * s, y * z * s
    local wx, wy, wz = w * x * s, w * y * s, w * z * s
    return {
        1 - yy - zz, xy - wz, xz + wy,
        xy + wz, 1 - xx - zz, yz - wx,
        xz - wy, yz + wx, 1 - xx - yy,
    }
end

local function mul3(a, b)
    local r = {}
    for i = 0, 2 do
        for j = 0, 2 do
            r[i * 3 + j + 1] = a[i * 3 + 1] * b[j + 1] + a[i * 3 + 2] * b[j + 4] + a[i * 3 + 3] * b[j + 7]
        end
    end
    return r
end

local mat = {}

--- Row-major 4x4 (16 numbers, translation in elements 4, 8, 12): the shape `transform` accepts.
function mat.identity()
    return { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 }
end

--- translate(t) * rotate(l) * scale(s) * rotate(r); every argument is optional.
function mat.compose(t, l, s, r)
    local tv = t and vec3(t, "translation") or { x = 0, y = 0, z = 0 }
    local sv = s and vec3(s, "scale") or { x = 1, y = 1, z = 1 }
    local a = mul3(mul3(quatMatrix(l), { sv.x, 0, 0, 0, sv.y, 0, 0, 0, sv.z }), quatMatrix(r))
    return {
        a[1], a[2], a[3], tv.x,
        a[4], a[5], a[6], tv.y,
        a[7], a[8], a[9], tv.z,
        0, 0, 0, 1,
    }
end

function mat.mul(a, b)
    local r = {}
    for i = 0, 3 do
        for j = 1, 4 do
            r[i * 4 + j] = a[i * 4 + 1] * b[j] + a[i * 4 + 2] * b[4 + j] + a[i * 4 + 3] * b[8 + j] + a[i * 4 + 4] * b[12 + j]
        end
    end
    return r
end

--- Transforms a point.
function mat.apply(m, v)
    local p = vec3(v, "point")
    return {
        x = m[1] * p.x + m[2] * p.y + m[3] * p.z + m[4],
        y = m[5] * p.x + m[6] * p.y + m[7] * p.z + m[8],
        z = m[9] * p.x + m[10] * p.y + m[11] * p.z + m[12],
    }
end

--- A `transform` value (table form or matrix) as a matrix.
function mat.from(tr)
    if tr == nil then return mat.identity() end
    if tr[16] ~= nil then return tr end
    return mat.compose(tr.t, tr.l, tr.s, tr.r)
end

display.quat, display.mat = quat, mat

-- ── Handles ────────────────────────────────────────────────────────────

local function copy(t)
    local r = {}
    for k, v in pairs(t) do r[k] = v end
    return r
end

local function checkNumber(name, v, min)
    if type(v) ~= "number" or v ~= v or (min and v < min) then
        error(("display.%s: expected a number%s, got %s"):format(name, min and (" >= " .. min) or "", tostring(v)), 3)
    end
end

local D = {}

D.__index = function(self, k)
    local m = D[k]
    if m ~= nil then return m end
    return self.h[k]
end

local function wrap(core)
    return setmetatable({ h = core, _tasks = {} }, D)
end

--- Wraps a core handle (`world:display(...)`) to get the sugar methods.
function display.wrap(core) return wrap(core) end

local function track(self, id)
    self._tasks[id] = true
    return id
end

local function after(self, ticks, fn)
    local id
    id = mc.schedule(ticks, function()
        self._tasks[id] = nil
        if self.h.valid then fn() end
    end)
    return track(self, id)
end

function D:set(props)
    local tr = props.transform
    if tr ~= nil then
        if self._rig then
            local rest = copy(props)
            rest.transform, rest.interp = nil, nil
            if next(rest) ~= nil then
                rest.interp = props.interp
                self.h:set(rest)
            end
            self._rig:_setLocal(self._name, tr, props.interp)
            return
        end
        self._tr = tr[16] == nil and tr or false
    end
    self.h:set(props)
end

--- `set` with an interpolation of `ticks` ticks (transform, opacity, background, shadow, ... are interpolated by the client).
function D:animate(props, ticks)
    checkNumber("animate ticks", ticks, 0)
    local p = copy(props)
    p.interp = ticks
    self:set(p)
end

function D:moveTo(pos, yaw, pitch) self.h:moveTo(pos, yaw, pitch) end
function D:attach(entity, offset) self.h:attach(entity, offset) end
function D:detach() self.h:detach() end
function D:show(player) self.h:show(player) end
function D:hide(player) self.h:hide(player) end
function D:onClick(fn) self.h:onClick(fn) end
function D:onAttack(fn) self.h:onAttack(fn) end
function D:node() return self end

function D:remove()
    for id in pairs(self._tasks) do mc.cancelTask(id) end
    self._tasks = {}
    self.h:remove()
end

--- Endless rotation about `axis`, one full turn per `period` ticks, in steps shorter than 180 degrees (the client
--- interpolates between rotations the short way). Rotates the right rotation `r`, i.e. about the model's own axes.
--- opts: step (ticks per update), dir (-1 reverses), phase (start angle, radians).
function D:spin(axis, period, opts)
    opts = opts or {}
    checkNumber("spin period", period, 2)
    if self._tr == false then error("display:spin needs a table transform, not a matrix", 2) end
    self:stopSpin()
    local step = opts.step or math.max(1, math.floor(period / 4))
    if step * 2 >= period then step = math.max(1, math.floor((period - 1) / 2)) end
    local d = TAU * step / period * (opts.dir or 1)
    local angle = opts.phase or 0
    local baseR = (self._tr or {}).r
    self._spin = mc.scheduleRepeating(0, step, function()
        if not self.h.valid then return self:stopSpin() end
        angle = angle + d
        local tr = copy(self._tr or {})
        tr.r = quat.mul(quat.axis(axis, angle), baseR)
        self:animate({ transform = tr }, step)
    end)
    track(self, self._spin)
    return self
end

function D:stopSpin()
    if self._spin then
        mc.cancelTask(self._spin)
        self._tasks[self._spin] = nil
        self._spin = nil
    end
end

--- Smooth move to `pos` over `ticks` (max 59, the client's limit).
function D:flyTo(pos, ticks)
    checkNumber("flyTo ticks", ticks, 0)
    self.h:set({ teleport = math.min(ticks, 59) })
    self.h:moveTo(pos)
end

--- Fades out over `ticks`: text goes transparent, other kinds shrink to nothing. `thenRemove` removes it afterwards.
function D:fade(ticks, thenRemove)
    checkNumber("fade ticks", ticks, 0)
    local kind = self.h.kind
    if kind == "text" then
        self:animate({ opacity = 4, background = 0 }, ticks)
    elseif kind ~= "interaction" then
        local tr = copy(self._tr or {})
        tr.s = 0
        self:animate({ transform = tr }, ticks)
    end
    if thenRemove then after(self, ticks, function() self:remove() end) end
end

--- Removes the display after `ticks`.
function D:life(ticks)
    checkNumber("life ticks", ticks, 0)
    after(self, ticks, function() self:remove() end)
    return self
end

-- ── Spawn helpers ──────────────────────────────────────────────────────

local function make(kind, world, pos, props, defaults)
    local p = {}
    if kind ~= "interaction" then
        p.interp, p.teleport, p.brightness = 0, 0, 15
    end
    for k, v in pairs(defaults or {}) do p[k] = v end
    for k, v in pairs(props or {}) do
        if k == "scale" or k == "rotation" or k == "offset" then
            -- folded into `transform` below
        else
            p[k] = v
        end
    end
    props = props or {}
    if p.transform == nil and (props.scale ~= nil or props.rotation ~= nil or props.offset ~= nil) then
        p.transform = { t = props.offset, l = props.rotation, s = props.scale }
    end
    local w = wrap(world:display(kind, pos, p))
    if p.transform ~= nil then w._tr = p.transform[16] == nil and p.transform or false end
    return w
end

--- Item display. `item` is an id or an item table. opts: any `world:display` property plus scale, rotation, offset.
function display.item(world, pos, item, opts)
    return make("item", world, pos, opts, { item = item })
end

function display.block(world, pos, block, opts)
    return make("block", world, pos, opts, { block = block })
end

function display.text(world, pos, text, opts)
    return make("text", world, pos, opts, { text = text, billboard = "center" })
end

-- A text display holding one space draws a plain rectangle of about 5 x 9 font pixels (0.025 blocks each) at
-- scale 1; these scales make it `w` x `h` blocks. The rectangle's origin is its bottom centre.
local QUAD_X, QUAD_Y = 8.0, 1 / (9 * 0.025)

local function quadTransform(w, h, t)
    return { t = t, s = { x = QUAD_X * w, y = QUAD_Y * h, z = 1 } }
end

--- Coloured rectangle (`argb`) of `w` x `h` blocks.
function display.quad(world, pos, argb, w, h, opts)
    checkNumber("quad w", w, 0)
    checkNumber("quad h", h, 0)
    opts = copy(opts or {})
    if opts.transform == nil and opts.scale == nil then opts.transform = quadTransform(w, h, opts.offset) end
    return make("text", world, pos, opts, { text = " ", background = argb, billboard = "center" })
end

-- ── Groups / rigs ──────────────────────────────────────────────────────

local Rig = {}

local function localMatrix(node) return mat.from(node.tr) end

function Rig:_chain(name)
    local node = self._nodes[name]
    local m = localMatrix(node)
    if node.parent then m = mat.mul(self:_chain(node.parent), m) end
    return m
end

function Rig:_final(name)
    local m = self:_chain(name)
    if self._m then m = mat.mul(self._m, m) end
    return m
end

function Rig:_apply(name, interp)
    local props = { transform = self:_final(name) }
    props.interp = interp
    self._nodes[name].w.h:set(props)
    for _, child in ipairs(self._nodes[name].children) do self:_apply(child, interp) end
end

function Rig:_setLocal(name, tr, interp)
    local node = self._nodes[name]
    if not node then error("display rig: no node '" .. tostring(name) .. "'", 3) end
    node.tr = tr
    node.w._tr = tr[16] == nil and tr or false
    self:_apply(name, interp)
end

--- Sets the parent transform of the whole rig; children follow (matrices multiply down the tree).
function Rig:transform(t, ticks)
    if ticks ~= nil then checkNumber("rig:transform ticks", ticks, 0) end
    self._m = mat.from(t)
    for _, name in ipairs(self._roots) do self:_apply(name, ticks) end
end

function Rig:node(name)
    local node = self._nodes[name]
    if not node then error("display rig: no node '" .. tostring(name) .. "'", 2) end
    return node.w
end

local function each(self, fn)
    for _, name in ipairs(self._order) do fn(self._nodes[name].w) end
end

function Rig:attach(entity, offset) each(self, function(w) w:attach(entity, offset) end) end
function Rig:detach() each(self, function(w) w:detach() end) end
function Rig:show(player) each(self, function(w) w:show(player) end) end
function Rig:hide(player) each(self, function(w) w:hide(player) end) end
function Rig:moveTo(pos, yaw, pitch) each(self, function(w) w:moveTo(pos, yaw, pitch) end) end
function Rig:remove() each(self, function(w) if w.h.valid then w:remove() end end) end

--- Applies the same non-transform properties to every node (`viewers`, `viewRange`, ...).
function Rig:set(props)
    each(self, function(w) w:set(props) end)
end

function Rig:life(ticks)
    checkNumber("life ticks", ticks, 0)
    after(self._nodes[self._order[1]].w, ticks, function() self:remove() end)
    return self
end

Rig.__index = function(self, k)
    local m = Rig[k]
    if m ~= nil then return m end
    if k == "valid" then
        local first = rawget(self, "_nodes")[rawget(self, "_order")[1]]
        return first ~= nil and first.w.h.valid
    end
end

--- spec: name -> { kind = "item"|"block"|"text", parent = name?, transform = ..., <any display property> }.
function display.group(world, pos, spec)
    local rig = setmetatable({ _nodes = {}, _order = {}, _roots = {} }, Rig)
    local state = {}

    local function visit(name)
        if state[name] == "done" then return end
        if state[name] == "busy" then error("display.group: parent cycle at '" .. name .. "'", 3) end
        state[name] = "busy"
        local def = spec[name]
        if def.parent then
            if spec[def.parent] == nil then
                error(("display.group: node '%s' has unknown parent '%s'"):format(name, def.parent), 3)
            end
            visit(def.parent)
        end
        local kind = def.kind
        if kind ~= "item" and kind ~= "block" and kind ~= "text" then
            error(("display.group: node '%s' has kind %s (item, block or text)"):format(name, tostring(kind)), 3)
        end
        local props = copy(def)
        props.kind, props.parent, props.transform = nil, nil, nil
        local w = make(kind, world, pos, props)
        w._rig, w._name = rig, name
        rig._nodes[name] = { w = w, parent = def.parent, tr = def.transform, children = {} }
        rig._order[#rig._order + 1] = name
        if def.parent then
            local siblings = rig._nodes[def.parent].children
            siblings[#siblings + 1] = name
        else
            rig._roots[#rig._roots + 1] = name
        end
        w._tr = def.transform and def.transform[16] == nil and def.transform or false
        state[name] = "done"
    end

    local names = {}
    for name in pairs(spec) do names[#names + 1] = name end
    table.sort(names)
    for _, name in ipairs(names) do visit(name) end
    for _, name in ipairs(rig._roots) do rig:_apply(name, 0) end
    return rig
end

-- ── Timelines ──────────────────────────────────────────────────────────

local ease = {
    linear = function(x) return x end,
    ["in"] = function(x) return x * x end,
    out = function(x) return 1 - (1 - x) * (1 - x) end,
    inOut = function(x) return x < 0.5 and 2 * x * x or 1 - 2 * (1 - x) * (1 - x) end,
}

local Timeline = {}
Timeline.__index = Timeline

local function lerp(a, b, t) return a + (b - a) * t end

local function lerpVec(a, b, t)
    return { x = lerp(a.x, b.x, t), y = lerp(a.y, b.y, t), z = lerp(a.z, b.z, t) }
end

local function eulerQuat(r)
    local v = vec3(r, "rot")
    return quat.euler(v.y, v.x, v.z)
end

-- channel -> { normalise(value), blend(a, b, t) }
local channels = {
    pos = { norm = function(v) return vec3(v, "pos") end, blend = lerpVec, out = "t" },
    scale = { norm = function(v) return vec3(v, "scale") end, blend = lerpVec, out = "s" },
    rot = { norm = eulerQuat, blend = quat.slerp, out = "l" },
}

--- def: { loop = bool, step = ticks, length = ticks?, tracks = { name = { {t =, pos =, rot =, scale =, ease =}, ... } } }
--- `rot` is {rx, ry, rz} radians. Keyframes fill missing channels by holding the neighbouring value.
function display.timeline(def)
    if type(def) ~= "table" or type(def.tracks) ~= "table" then error("display.timeline: tracks table required", 2) end
    local tl = setmetatable({ loop = def.loop and true or false, step = def.step or 2, tracks = {}, length = def.length or 0 }, Timeline)
    checkNumber("timeline step", tl.step, 1)
    for name, frames in pairs(def.tracks) do
        if #frames == 0 then error("display.timeline: track '" .. name .. "' has no keyframes", 2) end
        local sorted = copy(frames)
        table.sort(sorted, function(a, b) return a.t < b.t end)
        for _, f in ipairs(sorted) do
            checkNumber("timeline keyframe t", f.t, 0)
            if f.ease ~= nil and ease[f.ease] == nil then error("display.timeline: unknown ease '" .. tostring(f.ease) .. "'", 2) end
            for ch, spec in pairs(channels) do
                if f[ch] ~= nil then f["_" .. ch] = spec.norm(f[ch]) end
            end
        end
        tl.tracks[name] = sorted
        if def.length == nil and sorted[#sorted].t > tl.length then tl.length = sorted[#sorted].t end
    end
    return tl
end

local function sampleChannel(frames, ch, time)
    local blend = channels[ch].blend
    local prev, nextF
    for _, f in ipairs(frames) do
        if f["_" .. ch] ~= nil then
            if f.t <= time then prev = f end
            if f.t > time then nextF = f; break end
        end
    end
    if not prev and not nextF then return nil end
    if not prev then return nextF["_" .. ch] end
    if not nextF then return prev["_" .. ch] end
    local x = (time - prev.t) / (nextF.t - prev.t)
    return blend(prev["_" .. ch], nextF["_" .. ch], ease[nextF.ease or "linear"](x))
end

--- Transform table `{t, l, s}` of a track at `time` ticks.
function Timeline:sample(name, time)
    local frames = self.tracks[name]
    if not frames then error("display.timeline: no track '" .. tostring(name) .. "'", 2) end
    local tr = {}
    for ch, spec in pairs(channels) do tr[spec.out] = sampleChannel(frames, ch, time) end
    return tr
end

local Play = {}
Play.__index = Play

function Play:stop()
    if self._task then
        mc.cancelTask(self._task)
        self._task = nil
    end
    self.done = true
end

--- Plays `anim` on a rig (tracks name its nodes) or on a single display (its tracks all drive it).
--- Each step sets the pose one step ahead with an interpolation of `step` ticks.
function display.play(target, anim, opts)
    local step = (opts and opts.step) or anim.step
    checkNumber("play step", step, 1)
    local p = setmetatable({ done = false }, Play)
    local time = 0

    local function pose(t, interp)
        for name in pairs(anim.tracks) do
            target:node(name):set({ transform = anim:sample(name, t), interp = interp })
        end
    end

    pose(0, 0)
    if anim.length <= 0 then
        p.done = true
        return p
    end
    p._task = mc.scheduleRepeating(1, step, function()
        if target.valid == false then return p:stop() end
        time = time + step
        if time >= anim.length and not anim.loop then
            pose(anim.length, step)
            return p:stop()
        end
        pose(anim.loop and time % anim.length or time, step)
    end)
    return p
end

-- ── Bar ────────────────────────────────────────────────────────────────

local Bar = {}

--- Fill level 0..1, animated over `ticks`.
function Bar:set(frac, ticks)
    checkNumber("bar fraction", frac)
    frac = math.max(0, math.min(1, frac))
    local w, h = self._w, self._h
    local width = math.max(w * frac, 1e-4)
    local tr = { t = { x = -w / 2 + width / 2, y = 0, z = 0.002 }, s = { x = QUAD_X * width, y = QUAD_Y * h, z = 1 } }
    self.frac = frac
    self.rig:node("fg"):animate({ transform = tr }, ticks or 0)
end

Bar.__index = function(self, k)
    local m = Bar[k]
    if m ~= nil then return m end
    local f = self.rig[k]
    if type(f) == "function" then
        return function(_, ...) return f(self.rig, ...) end
    end
    return f
end

--- Horizontal bar of two rectangles. opts: w, h (blocks), fg, bg (ARGB), frac (start level, default 1), plus group
--- properties (viewRange, viewers, ...). The bar billboards; the rig methods (attach, show, remove, ...) work on it.
function display.bar(world, pos, opts)
    opts = copy(opts or {})
    local w, h = opts.w or 1.0, opts.h or 0.1
    local fg, bg = opts.fg or 0xFF33CC33, opts.bg or 0x80000000
    local frac = opts.frac or 1
    opts.w, opts.h, opts.fg, opts.bg, opts.frac = nil, nil, nil, nil, nil
    local function node(color, z)
        local n = copy(opts)
        n.kind, n.text, n.background, n.billboard = "text", " ", color, "center"
        n.transform = { t = { x = 0, y = 0, z = z }, s = { x = QUAD_X * w, y = QUAD_Y * h, z = 1 } }
        return n
    end
    local rig = display.group(world, pos, { bg = node(bg, 0), fg = node(fg, 0.002) })
    local bar = setmetatable({ rig = rig, _w = w, _h = h, frac = 1 }, Bar)
    if frac ~= 1 then bar:set(frac, 0) end
    return bar
end

return display
