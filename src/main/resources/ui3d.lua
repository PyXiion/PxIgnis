-- core:ui3d — flat UI panels in the world built from virtual displays. 1 unit = 1 block.
--
--   local ref = require "core:ref"
--   local ui  = require "core:ui3d"
--   local score = ref.new(0)
--   local p = ui.panel { at = pos, facing = "north", width = 3 } {
--     ui.col { gap = 0.05, pad = 0.1,
--       ui.text(function() return "Score: " .. score:get() end),
--       ui.button { "+1", onClick = function(player) score:update(function(n) return n + 1 end) end },
--     },
--   }
--   p:remove()
--
-- Trees are descriptors (plain tables); a panel instantiates them: layout engine, one set of displays per widget,
-- bindings for Ref/function props. Hover comes from a per-tick ray vs panel-plane test, clicks from one interaction
-- display ("shield") over the panel plus the same ray test.
local ref = require "core:ref"
local display = require "core:display"

local ui = {}

-- ── Constants ──────────────────────────────────────────────────────────

-- Scales that make a text-display background `w` x `h` blocks, and how far left of the quad's origin its rectangle
-- begins (fraction of `w`): see `core:display`.
local QUAD_X, QUAD_Y, QUAD_SKEW = display.QUAD.x, display.QUAD.y, display.QUAD.skew
local LINE = 9 * 0.025   -- line height at scale 1
local CHAR = 6 * 0.025   -- average glyph advance at scale 1
local DEFAULT_REACH = 8
local DEFAULT_RANGE = 24
local PRESS_TICKS = 4
local HOVER_INTERP = 3
local DEPTH = 0.003      -- distance between stacked layers of one widget
local OVERLAY_LIFT = 0.01 -- overlays sit this far in front of the widget

-- ── Geometry (pure) ────────────────────────────────────────────────────

-- facing: the direction the panel front faces (towards its viewers). theta: rotation about Y taking +Z to it.
local FACING = {
    south = { n = { x = 0, y = 0, z = 1 }, theta = 0 },
    west = { n = { x = -1, y = 0, z = 0 }, theta = -math.pi / 2 },
    north = { n = { x = 0, y = 0, z = -1 }, theta = math.pi },
    east = { n = { x = 1, y = 0, z = 0 }, theta = math.pi / 2 },
}

--- Frame of a panel of `w` x `h` blocks centred at `at`, front facing `facing`: origin `o`, unit axes `r` (to the
--- viewer's right), `u` (up), `n` (front normal), and `q` (rotation of the displays).
function ui.frame(at, facing, w, h)
    local f = FACING[facing or "south"]
    if not f then error("ui3d: unknown facing '" .. tostring(facing) .. "' (north, south, east, west)", 2) end
    local c, s = math.cos(f.theta), math.sin(f.theta)
    return {
        o = { x = at.x, y = at.y, z = at.z },
        r = { x = c, y = 0, z = -s }, u = { x = 0, y = 1, z = 0 }, n = f.n,
        w = w, h = h, q = display.quat.axis({ 0, 1, 0 }, f.theta),
    }
end

--- Offset from the panel centre of the point `u` right, `v` up, `z` in front.
function ui.offset(frame, u, v, z)
    local r, n = frame.r, frame.n
    return { x = r.x * u + n.x * z, y = v + n.y * z, z = r.z * u + n.z * z }
end

--- Intersects the ray `eye + t * dir` with the panel. Returns layout coordinates `x, y` (blocks from the top-left
--- corner, y down) and the ray distance `t`; nil when the ray is parallel, hits the back, is beyond `range` or misses.
function ui.project(frame, eye, dir, range)
    local n, o = frame.n, frame.o
    local denom = dir.x * n.x + dir.y * n.y + dir.z * n.z
    if denom > -1e-6 then return nil end
    local t = ((o.x - eye.x) * n.x + (o.y - eye.y) * n.y + (o.z - eye.z) * n.z) / denom
    if t <= 0 or (range and t > range) then return nil end
    local px, py, pz = eye.x + dir.x * t - o.x, eye.y + dir.y * t - o.y, eye.z + dir.z * t - o.z
    local r = frame.r
    local u = px * r.x + pz * r.z
    local x, y = u + frame.w / 2, frame.h / 2 - py
    if x < 0 or x > frame.w or y < 0 or y > frame.h then return nil end
    return x, y, t
end

-- ── Layout (pure) ──────────────────────────────────────────────────────

-- A node: { kind, p = props, children = {...}, rect }. Dynamic nodes (`dyn = true`: show/each) are transparent:
-- their children count as children of the parent.
local function flat(node, out)
    out = out or {}
    for _, c in ipairs(node.children or {}) do
        if c.dyn then flat(c, out) else out[#out + 1] = c end
    end
    return out
end

ui.flat = flat

local function growOf(node)
    local g = node.p.grow
    if g == true then return 1 end
    return type(g) == "number" and g or 0
end

local measure

local function intrinsic(node)
    local m = node.measure
    if m then return m(node) end
    return 0, 0
end

function measure(node)
    local p, kind = node.p, node.kind
    local w, h = 0, 0
    if kind == "col" or kind == "row" or kind == "stack" then
        local kids = flat(node)
        local gap, pad = p.gap or 0, p.pad or 0
        local main, cross = 0, 0
        for _, c in ipairs(kids) do
            local cw, ch = measure(c)
            if kind == "col" then main, cross = main + ch, math.max(cross, cw)
            elseif kind == "row" then main, cross = main + cw, math.max(cross, ch)
            else w, h = math.max(w, cw), math.max(h, ch) end
        end
        if kind == "stack" then
            w, h = w + 2 * pad, h + 2 * pad
        else
            main = main + gap * math.max(#kids - 1, 0)
            if kind == "col" then w, h = cross + 2 * pad, main + 2 * pad
            else w, h = main + 2 * pad, cross + 2 * pad end
        end
    else
        w, h = intrinsic(node)
    end
    if p.w then w = p.w end
    if p.h then h = p.h end
    node.nw, node.nh = w, h
    return w, h
end

local function place(align, pos, size, natural)
    if align == "center" then return pos + (size - natural) / 2 end
    if align == "end" then return pos + size - natural end
    return pos
end

local arrange

-- Size of a child along the cross axis: its fixed size, else the whole space (`stretch`) or its natural size.
local function crossSize(fixed, natural, space, align)
    if fixed then return fixed end
    if align == "stretch" or align == nil then return space end
    return natural
end

function arrange(node, x, y, w, h)
    node.rect = { x = x, y = y, w = w, h = h }
    local kind, p = node.kind, node.p
    if kind ~= "col" and kind ~= "row" and kind ~= "stack" then return end
    local kids = flat(node)
    local pad, gap, align = p.pad or 0, p.gap or 0, p.align or "stretch"
    local ix, iy, iw, ih = x + pad, y + pad, w - 2 * pad, h - 2 * pad
    if kind == "stack" then
        for _, c in ipairs(kids) do
            local cw = crossSize(c.p.w, c.nw, iw, align)
            local ch = crossSize(c.p.h, c.nh, ih, align)
            arrange(c, place(align, ix, iw, cw), place(align, iy, ih, ch), cw, ch)
        end
        return
    end
    local vertical = kind == "col"
    local space = vertical and ih or iw
    local used, weights = gap * math.max(#kids - 1, 0), 0
    for _, c in ipairs(kids) do
        used = used + (vertical and c.nh or c.nw)
        weights = weights + growOf(c)
    end
    local free = math.max(space - used, 0)
    local cursor = vertical and iy or ix
    for _, c in ipairs(kids) do
        local main = (vertical and c.nh or c.nw)
        if weights > 0 then main = main + free * growOf(c) / weights end
        if vertical then
            local cw = crossSize(c.p.w, c.nw, iw, align)
            arrange(c, place(align, ix, iw, cw), cursor, cw, main)
        else
            local ch = crossSize(c.p.h, c.nh, ih, align)
            arrange(c, cursor, place(align, iy, ih, ch), main, ch)
        end
        cursor = cursor + main + gap
    end
end

--- Lays `root` out in a `w` x `h` box (either may be nil: the natural size). Sets `rect` on every node; returns the
--- size used.
function ui.layout(root, w, h)
    measure(root)
    w, h = w or root.nw, h or root.nh
    arrange(root, 0, 0, w, h)
    return w, h
end

--- The interactive node under layout point `x, y`; later siblings (drawn on top) win.
function ui.hit(node, x, y)
    local kids = flat(node)
    for i = #kids, 1, -1 do
        local found = ui.hit(kids[i], x, y)
        if found then return found end
    end
    local r = node.rect
    if node.interactive and r and x >= r.x and x <= r.x + r.w and y >= r.y and y <= r.y + r.h then return node end
end

-- ── Styles ─────────────────────────────────────────────────────────────

local STATES = { hover = true, press = true, disabled = true }

local DEFAULT_THEME = {
    text = { fg = 0xFFFFFFFF },
    icon = {},
    box = {},
    button = {
        bg = 0xFF3A3A3A, fg = 0xFFFFFFFF,
        hover = { bg = 0xFF565656, scale = 1.05, z = 0.02 },
        press = { bg = 0xFF262626, scale = 0.96 },
        disabled = { bg = 0xFF2A2A2A, fg = 0xFF808080 },
        sound = { press = "minecraft:ui.button.click", hover = false, disabled = false, volume = 0.6, pitch = 1 },
    },
    danger = {
        bg = 0xFF8B2020, fg = 0xFFFFFFFF,
        hover = { bg = 0xFFB02A2A, scale = 1.05, z = 0.02 },
        press = { bg = 0xFF661818, scale = 0.96 },
        disabled = { bg = 0xFF3A2222, fg = 0xFF808080 },
        sound = { press = "minecraft:ui.button.click", hover = false, disabled = false, volume = 0.6, pitch = 0.8 },
    },
    bar = { bg = 0x80000000, fg = 0xFF33CC33 },
    toggle = {
        bg = 0xFF555555, on = 0xFF33CC33, fg = 0xFFFFFFFF,
        hover = { scale = 1.05, z = 0.02 },
        press = { scale = 0.95 },
        disabled = { bg = 0xFF2A2A2A, on = 0xFF1A6619, fg = 0xFF808080 },
        sound = { press = "minecraft:ui.button.click", hover = false, disabled = false, volume = 0.6, pitch = 1.2 },
    },
}

local function shallow(a, b)
    local r = {}
    for k, v in pairs(a or {}) do r[k] = v end
    for k, v in pairs(b or {}) do r[k] = v end
    return r
end

-- Fields of `b` over those of `a`; the state tables and `sound` merge field by field too.
local function mergeStyle(a, b)
    local r = {}
    for k, v in pairs(a or {}) do
        if STATES[k] or k == "sound" then r[k] = shallow(v) else r[k] = v end
    end
    for k, v in pairs(b or {}) do
        if STATES[k] or k == "sound" then r[k] = shallow(r[k], v) else r[k] = v end
    end
    return r
end

--- Default theme merged with `user` (style name -> fields, plus `hover`/`press`/`disabled` override tables).
function ui.theme(user)
    local t = {}
    for name, s in pairs(DEFAULT_THEME) do t[name] = s end
    for name, s in pairs(user or {}) do t[name] = mergeStyle(t[name], s) end
    return t
end

--- Effective style of a widget in `state` (nil, "hover", "press", "disabled").
function ui.resolveStyle(theme, name, state)
    local base = theme[name] or {}
    local r = {}
    for k, v in pairs(base) do
        if not STATES[k] then r[k] = v end
    end
    for k, v in pairs(state and base[state] or {}) do r[k] = v end
    return r
end

-- ── Widget visuals ─────────────────────────────────────────────────────

local function textLen(s)
    s = tostring(s):gsub("\194\167.", "")
    return #(s:gsub("[\128-\191]", ""))
end

local function hex(argb)
    return string.format("#%06x", argb % 0x1000000)
end

local function component(str, argb)
    return { text = tostring(str), color = hex(argb) }
end

local function sizeOf(node) return node.p.size or 0.2 end

local function labelWidth(node, str)
    return textLen(str) * CHAR * sizeOf(node) / LINE
end

local function spawn(node, kind, props)
    local ctx = node.ctx
    props.interp, props.teleport, props.brightness = 0, 0, 15
    props.viewers = ctx.viewers
    local h = display.wrap(ctx.world:display(kind, ctx.at, props))
    node.handles[#node.handles + 1] = h
    return h
end

local function quad(node, argb)
    return spawn(node, "text", { text = " ", background = argb, billboard = "fixed", transform = { s = 0 } })
end

local function label(node)
    return spawn(node, "text", {
        text = "", background = 0, billboard = "fixed", lineWidth = 1000, align = "center",
        textShadow = false, transform = { s = 0 },
    })
end

-- Centre (u, v) of the node's rectangle in panel-local coordinates (right, up from the panel centre).
local function centre(node, frame)
    local r = node.rect
    return r.x + r.w / 2 - frame.w / 2, frame.h / 2 - (r.y + r.h / 2)
end

-- Draws the rectangle of `w` x `hh` blocks centred on (u, v): the quad's origin is the bottom centre of that rectangle.
local function quadSet(h, frame, u, v, z, w, hh, argb, interp)
    w, hh = math.max(w, 1e-4), math.max(hh, 1e-4)
    h:set {
        interp = interp, background = argb,
        transform = {
            t = ui.offset(frame, u - QUAD_SKEW * w, v - hh / 2, z), l = frame.q,
            s = { x = QUAD_X * w, y = QUAD_Y * hh, z = 1 },
        },
    }
end

local function labelSet(node, h, frame, u, v, z, str, argb, k, interp)
    local s = sizeOf(node) / LINE * k
    h:set {
        interp = interp, text = component(str, argb),
        transform = { t = ui.offset(frame, u, v - LINE * s / 2, z), l = frame.q, s = { x = s, y = s, z = 1 } },
    }
end

local KINDS = {}

KINDS.box = { measure = function() return 0, 0 end, render = function() end }

KINDS.text = {
    measure = function(node)
        return labelWidth(node, node.p.text or ""), sizeOf(node)
    end,
    render = function(node, frame, st, interp)
        if not node.vis then node.vis = { label(node) } end
        local r = node.rect
        local str = node.p.text or ""
        local tw = labelWidth(node, str)
        local u, v = centre(node, frame)
        local al = node.p.align
        if al == "left" then u = r.x + tw / 2 - frame.w / 2
        elseif al == "right" then u = r.x + r.w - tw / 2 - frame.w / 2 end
        labelSet(node, node.vis[1], frame, u, v, st.z or 0, str, node.p.fg or st.fg, st.scale or 1, interp)
    end,
}

KINDS.button = {
    interactive = true,
    measure = function(node)
        return labelWidth(node, node.p.text or "") + 0.24, sizeOf(node) + 0.14
    end,
    render = function(node, frame, st, interp)
        if not node.vis then node.vis = { quad(node, 0), label(node) } end
        local r, k, z = node.rect, st.scale or 1, st.z or 0
        local u, v = centre(node, frame)
        quadSet(node.vis[1], frame, u, v, z, r.w * k, r.h * k, node.p.bg or st.bg, interp)
        labelSet(node, node.vis[2], frame, u, v, z + DEPTH, node.p.text or "", node.p.fg or st.fg, k, interp)
    end,
}

KINDS.bar = {
    measure = function() return 1, 0.12 end,
    render = function(node, frame, st, interp)
        if not node.vis then node.vis = { quad(node, 0), quad(node, 0) } end
        local r, k, z = node.rect, st.scale or 1, st.z or 0
        local u, v = centre(node, frame)
        local frac = math.max(0, math.min(1, tonumber(node.p.value) or 0))
        quadSet(node.vis[1], frame, u, v, z, r.w * k, r.h * k, node.p.bg or st.bg, interp)
        local fw = r.w * k * frac
        quadSet(node.vis[2], frame, u - r.w * k / 2 + fw / 2, v, z + DEPTH, fw, r.h * k, node.p.fg or st.fg, interp)
    end,
}

KINDS.toggle = {
    interactive = true,
    measure = function() return 0.4, 0.2 end,
    render = function(node, frame, st, interp)
        if not node.vis then node.vis = { quad(node, 0), quad(node, 0) } end
        local r, k, z = node.rect, st.scale or 1, st.z or 0
        local u, v = centre(node, frame)
        local on = node.p.value and true or false
        quadSet(node.vis[1], frame, u, v, z, r.w * k, r.h * k, on and (node.p.on or st.on) or (node.p.bg or st.bg), interp)
        local knob = math.min(r.w, r.h) * 0.8 * k
        local ku = on and (u + r.w * k / 2 - knob / 2 - r.h * k * 0.1) or (u - r.w * k / 2 + knob / 2 + r.h * k * 0.1)
        quadSet(node.vis[2], frame, ku, v, z + DEPTH, knob, knob, node.p.fg or st.fg, interp)
    end,
}

KINDS.icon = {
    measure = function(node) return sizeOf(node) * 2, sizeOf(node) * 2 end,
    render = function(node, frame, st, interp)
        local r, k = node.rect, st.scale or 1
        if not node.vis then
            node.vis = { spawn(node, "item", { item = node.p.item or "minecraft:air", mode = "gui", billboard = "fixed", transform = { s = 0 } }) }
        end
        local u, v = centre(node, frame)
        local sz = math.min(r.w, r.h) * k
        node.vis[1]:set {
            interp = interp, item = node.p.item or "minecraft:air",
            transform = { t = ui.offset(frame, u, v, st.z or 0), l = frame.q, s = { x = sz, y = sz, z = 0.01 } },
        }
    end,
}

-- Props whose change alters the layout.
local LAYOUT = { w = true, h = true, gap = true, pad = true, grow = true, align = true, text = true, size = true }

-- ── Nodes ──────────────────────────────────────────────────────────────

local Node = {}
Node.__index = Node

local function stateOf(node)
    if node.p.disabled then return "disabled" end
    if node.pressed then return "press" end
    if node.hovered then return "hover" end
end

--- Updates the displays of this node from its props, rectangle and state. `interp`: client-side animation ticks.
function Node:render(interp)
    local kind = KINDS[self.kind]
    if not kind or not kind.render or not self.rect or self.removed then return end
    local inst = self.ctx.inst
    local style = ui.resolveStyle(self.ctx.theme, self.p.style or self.kind, stateOf(self))
    self.placed = { self.rect.x, self.rect.y, self.rect.w, self.rect.h }
    kind.render(self, inst.frame, style, interp or 0)
    if self.overlays then
        for which in pairs(self.overlays) do self:renderOverlay(which, interp) end
    end
end

-- Per-viewer looks of a widget on a shared panel: an overlay is a second set of displays in the hover (`"hover"`) or
-- press (`"press"`) style on top of the widget, with manual viewers, shown only to the players it concerns. It is a
-- view of the node (`__index`) with its own displays; the kind's render function draws into it unchanged.
function Node:renderOverlay(which, interp)
    local style = ui.resolveStyle(self.ctx.theme, self.p.style or self.kind, which)
    style.z = (style.z or 0) + OVERLAY_LIFT
    KINDS[self.kind].render(self.overlays[which], self.ctx.inst.frame, style, interp or 0)
end

function Node:ensureOverlay(which)
    self.overlays = self.overlays or {}
    if self.overlays[which] then return self.overlays[which] end
    local ctx = setmetatable({ viewers = "manual" }, { __index = self.ctx })
    self.overlays[which] = setmetatable({ vis = false, handles = {}, ctx = ctx }, { __index = self })
    self:renderOverlay(which, 0)
    return self.overlays[which]
end

function Node:overlayShow(which, player)
    for _, h in ipairs(self:ensureOverlay(which).handles) do h:show(player) end
end

function Node:overlayHide(which, player)
    local o = self.overlays and self.overlays[which]
    if not o then return end
    for _, h in ipairs(o.handles) do h:hide(player) end
end

--- Sets props (plain values) and refreshes.
function Node:set(props)
    local relayout = false
    for k, v in pairs(props) do
        self.p[k] = v
        if LAYOUT[k] then relayout = true end
    end
    if relayout then self.ctx.inst:relayout() end
    self:render(0)
end

function Node:dispose()
    if self.removed then return end
    self.removed = true
    for _, c in ipairs(self.children) do c:dispose() end
    self.children = {}
    for _, off in ipairs(self.disposers) do off() end
    self.disposers = {}
    for _, h in ipairs(self.handles) do
        if h.h.valid then h:remove() end
    end
    self.handles = {}
    for _, o in pairs(self.overlays or {}) do
        for _, h in ipairs(o.handles) do
            if h.h.valid then h:remove() end
        end
    end
    self.overlays = nil
end

local function truthy(v) return v ~= nil and v ~= false end

-- "viewer" (an overlay only the player concerned sees) or "shared" (the widget itself changes, for everyone).
local function modeOf(node, what)
    return node.p[what] or node.ctx.inst.panel[what]
end

--- Calls `apply(value)` now and on every change of `source` (plain value, Ref or function); returns the first value.
local function bindSource(node, source, apply)
    if ref.isRef(source) then
        node.disposers[#node.disposers + 1] = source:watch(function(new) apply(new) end)
        return source:peek()
    elseif type(source) == "function" then
        local c = ref.computed(source)
        node.disposers[#node.disposers + 1] = c:watch(function(new) apply(new) end)
        node.disposers[#node.disposers + 1] = function() c:dispose() end
        return c:peek()
    end
    return source
end

local instantiate

local function newNode(desc, ctx, parent)
    return setmetatable({
        kind = desc.kind, desc = desc, ctx = ctx, parent = parent, p = {}, children = {}, disposers = {}, handles = {},
        id = desc.id, interactive = KINDS[desc.kind] and KINDS[desc.kind].interactive,
        measure = KINDS[desc.kind] and KINDS[desc.kind].measure,
    }, Node)
end

local function buildChildren(node, descs)
    for _, d in ipairs(descs) do
        node.children[#node.children + 1] = instantiate(d, node.ctx, node)
    end
end

local function instantiateDyn(desc, ctx, parent)
    local node = newNode(desc, ctx, parent)
    node.dyn = true
    local built = false
    local function descs(v)
        if desc.kind == "show" then return truthy(v) and { desc.node } or {} end
        local out = {}
        for i, item in ipairs(v or {}) do out[#out + 1] = desc.fn(item, i) end
        return out
    end
    local function rebuild(v)
        for _, c in ipairs(node.children) do c:dispose() end
        node.children = {}
        buildChildren(node, descs(v))
        if built then ctx.inst:relayout() end
    end
    local first = bindSource(node, desc.source, rebuild)
    rebuild(first)
    built = true
    return node
end

function instantiate(desc, ctx, parent)
    if desc.dyn then return instantiateDyn(desc, ctx, parent) end
    local node = newNode(desc, ctx, parent)
    for k, v in pairs(desc) do
        if type(k) == "string" and k ~= "kind" and k ~= "id" and k ~= "children" and k ~= "style" and not k:match("^on%u") then
            node.p[k] = bindSource(node, v, function(new)
                node.p[k] = new
                if LAYOUT[k] then ctx.inst:relayout() end
                node:render(0)
            end)
        end
    end
    node.p.style = desc.style
    node.handlers = { click = desc.onClick, change = desc.onChange }
    buildChildren(node, desc.children or {})
    return node
end

-- ── Descriptors (the DSL) ──────────────────────────────────────────────

local function container(kind)
    return function(props)
        local d = { kind = kind, children = {} }
        for k, v in pairs(props) do
            if type(k) == "number" then d.children[#d.children + 1] = v else d[k] = v end
        end
        return d
    end
end

ui.col, ui.row, ui.stack = container("col"), container("row"), container("stack")

local function leaf(kind, primary)
    return function(props)
        if type(props) ~= "table" or ref.isRef(props) then props = { props } end
        local d = { kind = kind }
        for k, v in pairs(props) do d[k] = v end
        if d[1] ~= nil then d[primary], d[1] = d[1], nil end
        return d
    end
end

--- `ui.text("str" | ref | fn)` or `ui.text { "str", size =, fg =, align = }`.
ui.text = leaf("text", "text")
--- `ui.button { "label", onClick = fn(player), style =, disabled = }`.
ui.button = leaf("button", "text")
--- `ui.icon("minecraft:apple")` or `ui.icon { item =, size = }`.
ui.icon = leaf("icon", "item")
--- `ui.bar { value = 0..1 }`.
ui.bar = leaf("bar", "value")
--- `ui.toggle { value = ref|bool, onChange = fn(new, player) }`.
ui.toggle = leaf("toggle", "value")
--- Empty spacer: `ui.box { w =, h =, grow = }`.
ui.box = leaf("box", "w")

--- Shows `node` while `cond` (value, Ref or function) is truthy.
function ui.show(cond, node)
    return { kind = "show", dyn = true, source = cond, node = node }
end

--- One `fn(item, index)` descriptor per entry of `list` (table, Ref or function); the whole group rebuilds on change.
function ui.each(list, fn)
    return { kind = "each", dyn = true, source = list, fn = fn }
end

-- ── Panel ──────────────────────────────────────────────────────────────

local Instance = {}
Instance.__index = Instance

local function walk(node, fn)
    fn(node)
    for _, c in ipairs(node.children) do walk(c, fn) end
end

function Instance:relayout()
    local panel = self.panel
    local w, h = ui.layout(self.root, panel.width, panel.height)
    local oldFrame = self.frame
    local frame = ui.frame(panel.at, panel.facing, w, h)
    self.frame = frame
    local full = not oldFrame or oldFrame.w ~= w or oldFrame.h ~= h
    if full then
        self.shield:set { hitbox = { w = math.max(w, 0.1), h = h, responsive = true } }
        self.shield:moveTo({ x = panel.at.x, y = panel.at.y - h / 2, z = panel.at.z })
    end
    walk(self.root, function(node)
        local r = node.rect
        local pl = node.placed
        if not node.dyn and r and (full or not pl or pl[1] ~= r.x or pl[2] ~= r.y or pl[3] ~= r.w or pl[4] ~= r.h) then
            node:render(0)
        end
    end)
end

local function find(root, id)
    local found
    walk(root, function(n) if not found and n.id == id then found = n end end)
    return found
end

function Instance:activate(node, player)
    local p = node.p
    if p.disabled then
        self:sound(node, "disabled", player)
        return
    end
    self:sound(node, "press", player)
    if node.kind == "toggle" then
        local src, new = node.desc.value, not truthy(p.value)
        if ref.isRef(src) then
            src:set(new)
        else
            p.value = new
            node:render(HOVER_INTERP)
        end
        if node.handlers.change then node.handlers.change(new, player) end
        return
    end
    if self.viewers or modeOf(node, "press") == "shared" then
        node.pressed = true
        node:render(HOVER_INTERP)
        mc.schedule(PRESS_TICKS, function()
            if node.removed then return end
            node.pressed = false
            node:render(HOVER_INTERP)
        end)
    else
        self:pressFor(node, player)
    end
    if node.handlers.click then node.handlers.click(player) end
end

-- Plays the style's `sound[which]` ("hover", "press", "disabled") for a widget: to `player` alone when the look is per
-- viewer, at the widget for nearby players when it is shared. A per-player panel has one viewer: always that player.
function Instance:sound(node, which, player)
    if which == "hover" and node.p.disabled then return end
    local snd = ui.resolveStyle(self.panel.theme, node.p.style or node.kind).sound
    local id = snd and snd[which]
    if not id then return end
    local volume, pitch = snd.volume or 1, snd.pitch or 1
    if self.viewers or modeOf(node, which == "hover" and "hover" or "press") == "viewer" then
        (self.player or player):playSound(id, volume, pitch)
    else
        local panel = self.panel
        local cu, cv = centre(node, self.frame)
        local o = ui.offset(self.frame, cu, cv, 0)
        panel.world:playSound(id, { x = panel.at.x + o.x, y = panel.at.y + o.y, z = panel.at.z + o.z }, volume, pitch)
    end
end

-- Press look for one player of a shared panel; their hover overlay on that widget gives way meanwhile.
function Instance:pressFor(node, player)
    local key = player.uuid
    if self.shown[key] == node then
        node:overlayHide("hover", player)
        self.shown[key] = nil
    end
    local token = { node = node }
    self.pressing[key] = token
    node:overlayShow("press", player)
    mc.schedule(PRESS_TICKS, function()
        if self.pressing[key] == token then self.pressing[key] = nil end
        if node.removed or self.removed then return end
        node:overlayHide("press", player)
    end)
end

--- The interactive node a player's view ray points at (no occlusion check).
function Instance:pick(player, reach)
    local x, y = ui.project(self.frame, player.eyePos, player.dir, reach)
    if not x then return nil end
    return ui.hit(self.root, x, y)
end

function Instance:click(player, hand)
    if hand == "off" or self.removed then return end
    local node = self:pick(player, self.panel.reach)
    if node then self:activate(node, player) end
end

local function dist(a, b)
    local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
    return math.sqrt(dx * dx + dy * dy + dz * dz)
end

--- The node `player` hovers: on the panel, under the ray, with no block in between.
function Instance:hovered(player)
    local panel = self.panel
    if not panel:inWorld(player) then return nil end
    local eye, dir = player.eyePos, player.dir
    local x, y, t = ui.project(self.frame, eye, dir, panel.reach)
    if not x then return nil end
    local node = ui.hit(self.root, x, y)
    if not node then return nil end
    local block = panel.world:raycast(eye, dir, t, false, false)
    if block and block.hit and dist(block.hit, eye) < t - 0.05 then return nil end
    return node
end

-- Shared panel: a widget in "viewer" hover mode shows its hover overlay to the players pointing at it; one in "shared"
-- mode takes the hover look itself while any player points at it.
function Instance:tickShared()
    local shown, pressing, seen, sharedHover = self.shown, self.pressing, {}, {}
    for _, player in ipairs(mc.players) do
        local key = player.uuid
        seen[key] = true
        local want = self:hovered(player)
        if want and want.p.disabled then want = nil end
        if want and modeOf(want, "hover") == "shared" then
            sharedHover[want] = true
            want = nil
        end
        if want and pressing[key] and pressing[key].node == want then want = nil end
        local cur = shown[key]
        if cur and cur.removed then cur = nil end
        if cur ~= want then
            if cur then cur:overlayHide("hover", player) end
            if want then
                want:overlayShow("hover", player)
                self:sound(want, "hover", player)
            end
            shown[key] = want
        end
    end
    -- players that left, died or changed world lose their overlay viewers with the display manager
    for key in pairs(shown) do
        if not seen[key] then shown[key] = nil end
    end
    for key in pairs(pressing) do
        if not seen[key] then pressing[key] = nil end
    end
    walk(self.root, function(node)
        if node.interactive then
            local now = sharedHover[node] == true
            if (node.hovered or false) ~= now then
                node.hovered = now
                node:render(HOVER_INTERP)
                if now then self:sound(node, "hover") end
            end
        end
    end)
end

function Instance:tick()
    if not self.viewers then return self:tickShared() end
    local hovered = {}
    for _, player in ipairs(self.viewers or mc.players) do
        local node = self:hovered(player)
        if node then hovered[node] = true end
    end
    walk(self.root, function(node)
        if node.interactive then
            local now = hovered[node] == true
            if (node.hovered or false) ~= now then
                node.hovered = now
                node:render(HOVER_INTERP)
                if now then self:sound(node, "hover") end
            end
        end
    end)
end

function Instance:dispose()
    if self.removed then return end
    self.removed = true
    self.root:dispose()
    if self.shield.h.valid then self.shield:remove() end
end

local Panel = {}
Panel.__index = Panel

local function newInstance(panel, tree, player)
    local viewers = player and { player } or nil
    local inst = setmetatable({ panel = panel, viewers = viewers, player = player, shown = {}, pressing = {} }, Instance)
    local ctx = {
        inst = inst, world = panel.world, at = panel.at, theme = panel.theme,
        viewers = viewers or "auto",
    }
    local desc = type(tree) == "function" and tree(player) or tree
    if desc.kind == nil then desc = ui.stack(desc) end -- `{ node, node }`: layers on top of each other
    inst.shield = display.wrap(panel.world:display("interaction", panel.at, {
        hitbox = { w = 0.1, h = 0.1, responsive = true }, viewers = ctx.viewers,
    }))
    inst.shield:onClick(function(p, hand) inst:click(p, hand) end)
    inst.shield:onAttack(function(p) inst:click(p) end)
    inst.root = instantiate({ kind = "stack", children = { desc } }, ctx, nil)
    inst:relayout()
    return inst
end

local function sameWorld(panel, player)
    local w = player.world
    return w == nil or w.name == panel.world.name
end

local function near(panel, player)
    return sameWorld(panel, player) and dist(player.pos, panel.at) <= panel.range
end

function Panel:inWorld(player)
    return sameWorld(self, player)
end

-- Per-player panels: one instance per player within range; a player who left, moved away or changed world loses it.
function Panel:sync()
    local seen = {}
    for _, player in ipairs(mc.players) do
        local key = player.uuid
        if near(self, player) then
            seen[key] = true
            if not self.instances[key] then self.instances[key] = newInstance(self, self.tree, player) end
        end
    end
    for key, inst in pairs(self.instances) do
        if not seen[key] then
            inst:dispose()
            self.instances[key] = nil
        end
    end
end

function Panel:update()
    if self.perPlayer then self:sync() end
    for _, inst in pairs(self.instances) do inst:tick() end
end

--- The widget with `id`. Per-player panels need the `player` whose tree to search.
function Panel:get(id, player)
    if self.perPlayer then
        local inst = player and self.instances[player.uuid]
        return inst and find(inst.root, id)
    end
    return find(self.instances.shared.root, id)
end

function Panel:remove()
    if self.removed then return end
    self.removed = true
    mc.cancelTask(self.task)
    for _, inst in pairs(self.instances) do inst:dispose() end
    self.instances = {}
end

--- `ui.panel { at =, facing =, width =, height =, world =, theme =, reach =, range = } (tree)`.
--- `tree` is a descriptor, or `function(player)` returning one (a separate tree per player within `range`).
function ui.panel(opts)
    if type(opts) ~= "table" or opts.at == nil then error("ui3d.panel: `at` is required", 2) end
    if not FACING[opts.facing or "south"] then
        error("ui3d.panel: unknown facing '" .. tostring(opts.facing) .. "' (north, south, east, west)", 2)
    end
    for _, what in ipairs { "hover", "press" } do
        local m = opts[what]
        if m ~= nil and m ~= "viewer" and m ~= "shared" then
            error("ui3d.panel: " .. what .. " must be \"viewer\" or \"shared\", got " .. tostring(m), 2)
        end
    end
    return function(tree)
        local panel = setmetatable({
            hover = opts.hover or "viewer", press = opts.press or "viewer",
            at = { x = opts.at.x, y = opts.at.y, z = opts.at.z },
            facing = opts.facing or "south", width = opts.width, height = opts.height,
            world = opts.world or mc.world("minecraft.overworld"),
            theme = ui.theme(opts.theme), reach = opts.reach or DEFAULT_REACH, range = opts.range or DEFAULT_RANGE,
            tree = tree, perPlayer = type(tree) == "function", instances = {},
        }, Panel)
        if panel.perPlayer then
            panel:sync()
        else
            panel.instances.shared = newInstance(panel, tree, nil)
        end
        panel.task = mc.scheduleRepeating(1, 1, function() panel:update() end)
        return panel
    end
end

return ui
