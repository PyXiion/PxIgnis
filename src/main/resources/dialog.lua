-- ==========================================================================
-- dialog.lua — server dialogs (Minecraft 1.21.6+) for PxIgnis
-- ==========================================================================
-- Usage: local dialog = require "core:dialog"
--
--   dialog.show(player, {
--       title = "Shop",
--       body = { "Pick a deal", dialog.item(stack, "A shiny sword") },
--       inputs = { dialog.number("amount", "Amount", 1, 64, { step = 1, initial = 1 }) },
--       buttons = {
--           dialog.button("Buy", function(player, values) ... end),
--           dialog.link("Rules", "https://example.com"),
--       },
--       onClose = function(player, reason) end,
--   })
--
-- One dialog per player is active at a time. Every button sends a one-shot `pxignis:d/<token>/<n>` custom click
-- (`player.custom_click`); clicks with a stale or foreign token are dropped, and input values are checked against
-- the spec (length, range and step, option ids) before a handler runs. The vanilla layer is `player:showDialog`.
-- ==========================================================================

local dialog = {}

local current = {} -- player.uuid -> the active dialog
local ID_PREFIX = "pxignis:d/"
local MAX_TEXT = 32

local function warn(text)
    mc.warn("core:dialog", text)
end

-- ---------------------------------------------------------------------------
-- Constructors
-- ---------------------------------------------------------------------------

---@param stack Item|string
---@param text? string
---@param opts? {tooltip?: boolean, decorations?: boolean, width?: integer, height?: integer}
function dialog.item(stack, text, opts)
    opts = opts or {}
    return { _kind = "item", item = stack, text = text, opts = opts }
end

function dialog.text(key, label, opts)
    return { _kind = "text", key = key, label = label, opts = opts or {} }
end

function dialog.number(key, label, min, max, opts)
    return { _kind = "number", key = key, label = label, min = min, max = max, opts = opts or {} }
end

function dialog.option(key, label, options, opts)
    return { _kind = "option", key = key, label = label, options = options, opts = opts or {} }
end

function dialog.bool(key, label, opts)
    return { _kind = "bool", key = key, label = label, opts = opts or {} }
end

function dialog.button(label, fn, opts)
    return { _kind = "button", label = label, fn = fn, opts = opts or {} }
end

function dialog.command(label, cmd, opts)
    return { _kind = "command", label = label, command = cmd, opts = opts or {} }
end

function dialog.link(label, url)
    return { _kind = "link", label = label, url = url, opts = {} }
end

function dialog.copy(label, text)
    return { _kind = "copy", label = label, text = text, opts = {} }
end

-- ---------------------------------------------------------------------------
-- Building the vanilla dialog
-- ---------------------------------------------------------------------------

-- A list that may contain nil/false holes (`cond and dialog.button(...)`): the entries that are set, in order.
local function entries(list, what)
    if list == nil then return {} end
    if type(list) ~= "table" then error("dialog: " .. what .. " must be a list", 3) end
    local n = 0
    for k in pairs(list) do
        if type(k) ~= "number" or k < 1 or k ~= math.floor(k) then
            error("dialog: " .. what .. " must be a list", 3)
        end
        if k > n then n = k end
    end
    local out = {}
    for i = 1, n do
        if list[i] then out[#out + 1] = list[i] end
    end
    return out
end

local function need(cond, msg)
    if not cond then error("dialog: " .. msg, 3) end
end

local function bodyJson(entry)
    if type(entry) == "string" then
        return { type = "minecraft:plain_message", contents = entry }
    end
    need(type(entry) == "table" and entry._kind == "item", "a body entry is a string or dialog.item(...)")
    local item = entry.item
    if type(item) == "string" then item = { id = item, count = 1 } end
    local o = entry.opts
    local b = { type = "minecraft:item", item = item, show_tooltip = o.tooltip, show_decorations = o.decorations,
        width = o.width, height = o.height }
    if entry.text then b.description = { contents = entry.text } end
    return b
end

local function inputJson(inp, seen)
    need(type(inp) == "table" and inp._kind and inp.key, "inputs are made with dialog.text/number/option/bool")
    need(type(inp.key) == "string" and inp.key:match("^[%a_][%w_]*$"), "input key '" .. tostring(inp.key) .. "' must be a name")
    need(not seen[inp.key], "duplicate input key '" .. inp.key .. "'")
    need(type(inp.label) == "string", "input '" .. inp.key .. "' needs a label")
    seen[inp.key] = true
    local o = inp.opts
    local rule = { key = inp.key, kind = inp._kind }
    local json = { key = inp.key, label = inp.label, width = o.width }
    if inp._kind == "text" then
        rule.max = o.max or MAX_TEXT
        json.type = "minecraft:text"
        json.max_length = rule.max
        json.initial = o.initial
        if o.hideLabel then json.label_visible = false end
        if o.lines or o.height then json.multiline = { max_lines = o.lines, height = o.height } end
    elseif inp._kind == "number" then
        need(type(inp.min) == "number" and type(inp.max) == "number" and inp.min < inp.max,
            "number '" .. inp.key .. "' needs min < max")
        rule.min, rule.max, rule.step = inp.min, inp.max, o.step
        json.type = "minecraft:number_range"
        json.start = inp.min
        json["end"] = inp.max
        json.step = o.step
        json.initial = o.initial
        json.label_format = o.format
    elseif inp._kind == "option" then
        local list = entries(inp.options, "options of '" .. inp.key .. "'")
        need(#list > 0, "option '" .. inp.key .. "' needs options")
        rule.ids = {}
        json.type = "minecraft:single_option"
        json.options = {}
        if o.hideLabel then json.label_visible = false end
        for i, opt in ipairs(list) do
            local id, label = opt[1] or opt.id, opt[2] or opt.label
            need(type(id) == "string" and type(label) == "string", "options are {id, label} pairs")
            need(not rule.ids[id], "duplicate option id '" .. id .. "'")
            rule.ids[id] = true
            json.options[i] = { id = id, display = label, initial = (o.initial == id) or nil }
        end
        need(o.initial == nil or rule.ids[o.initial], "initial option '" .. tostring(o.initial) .. "' is not in the list")
    else
        json.type = "minecraft:boolean"
        json.initial = o.initial
    end
    return json, rule
end

-- The vanilla after_action: "close" when every button is a server button that wants it, otherwise "none".
-- Never "wait_for_response": ClientboundClearDialogPacket does not dismiss the client's waiting screen (it only
-- handles dialog and warning screens), so a click with nothing to follow would leave the player stuck there.
-- With "none" the dialog stays up and the server clears it, or replaces it, after the handler.
local function vanillaAfter(buttons)
    local all
    for _, b in ipairs(buttons) do
        local after = (b._kind == "button" or b._kind == "command") and (b.opts.after or "wait") or "other"
        if all == nil then all = after elseif all ~= after then return "none" end
    end
    if all == "close" then return "close" end
    return "none"
end

local function buttonJson(b, token, n)
    need(type(b) == "table" and b._kind, "buttons are made with dialog.button/command/link/copy")
    need(type(b.label) == "string", "a button needs a label")
    local o = b.opts
    local action
    if b._kind == "button" then
        need(type(b.fn) == "function", "dialog.button needs a function")
    elseif b._kind == "command" then
        need(type(b.command) == "string", "dialog.command needs a command")
    elseif b._kind == "link" then
        need(type(b.url) == "string", "dialog.link needs a url")
        action = { type = "minecraft:open_url", url = b.url }
    else
        need(type(b.text) == "string", "dialog.copy needs a text")
        action = { type = "minecraft:copy_to_clipboard", value = b.text }
    end
    if o.after ~= nil then
        need(o.after == "wait" or o.after == "keep" or o.after == "close", "button 'after' is wait, keep or close")
    end
    action = action or { type = "minecraft:dynamic/custom", id = ID_PREFIX .. token .. "/" .. n }
    return { label = b.label, tooltip = o.tooltip, width = o.width, action = action }
end

local function randomToken()
    return string.format("%04x%04x%04x%04x", math.random(0, 0xffff), math.random(0, 0xffff), math.random(0, 0xffff),
        math.random(0, 0xffff))
end

-- ---------------------------------------------------------------------------
-- Closing
-- ---------------------------------------------------------------------------

-- Ends [d]: no click of it is accepted any more, and onClose runs once with the reason.
local function finish(d, reason)
    if d.done then return end
    d.done = true
    if current[d.uuid] == d then current[d.uuid] = nil end
    if d.onClose then
        local ok, err = pcall(d.onClose, d.player, reason)
        if not ok then warn("onClose: " .. tostring(err)) end
    end
end

function dialog.show(player, spec)
    need(type(spec) == "table", "show(player, spec): spec must be a table")
    need(type(spec.title) == "string", "a dialog needs a title")
    need(spec.onClose == nil or type(spec.onClose) == "function", "onClose must be a function")

    local token = randomToken()
    local seen, inputs, inputJsons = {}, {}, {}
    for _, inp in ipairs(entries(spec.inputs, "inputs")) do
        local json, rule = inputJson(inp, seen)
        inputJsons[#inputJsons + 1] = json
        inputs[#inputs + 1] = rule
    end
    local body = {}
    for _, entry in ipairs(entries(spec.body, "body")) do body[#body + 1] = bodyJson(entry) end

    local buttons = entries(spec.buttons, "buttons")
    local canEscape = spec.canEscape ~= false
    local escapeAction = { type = "minecraft:dynamic/custom", id = ID_PREFIX .. token .. "/x" }

    local d = {
        uuid = player.uuid, player = player, token = token, inputs = inputs, actions = {},
        onClose = spec.onClose, done = false,
    }
    local buttonJsons = {}
    for i, b in ipairs(buttons) do
        buttonJsons[i] = buttonJson(b, token, i)
        if b._kind == "button" or b._kind == "command" then
            d.actions[i] = { kind = b._kind, fn = b.fn, command = b.command, after = b.opts.after or "wait" }
        end
    end
    d.vanilla = (#buttons == 0) and "close" or vanillaAfter(buttons)

    local json = {
        title = spec.title, external_title = spec.externalTitle, can_close_with_escape = canEscape, pause = false,
        after_action = d.vanilla, body = #body > 0 and body or nil, inputs = #inputJsons > 0 and inputJsons or nil,
    }
    if #buttons == 0 then
        json.type = "minecraft:notice"
        json.action = { label = "OK", action = escapeAction }
    elseif #buttons == 1 and not canEscape then
        -- A notice's escape runs its button, so it is used only when escape is off.
        json.type = "minecraft:notice"
        json.action = buttonJsons[1]
    else
        json.type = "minecraft:multi_action"
        json.actions = buttonJsons
        json.columns = spec.columns
        if canEscape then json.exit_action = { label = "x", action = escapeAction } end
    end

    player:showDialog(json)

    local old = current[d.uuid]
    if old then finish(old, "replaced") end
    current[d.uuid] = d
end

--- Closes the player's dialog from this library, if any (onClose gets "closed").
function dialog.close(player)
    local d = current[player.uuid]
    if d then
        finish(d, "closed")
        player:closeDialog()
    end
end

-- ---------------------------------------------------------------------------
-- Values coming back from the client
-- ---------------------------------------------------------------------------

local function charCount(s)
    return select(2, s:gsub("[^\128-\191]", ""))
end

-- Checks the click payload against the input rules: the values by key, or nil and the reason.
local function validate(rules, payload)
    if payload ~= nil and type(payload) ~= "table" then return nil, "payload is not a table" end
    payload = payload or {}
    local values, known = {}, {}
    for _, r in ipairs(rules) do
        known[r.key] = true
        local v = payload[r.key]
        if r.kind == "text" then
            if type(v) ~= "string" then return nil, "'" .. r.key .. "' is not text" end
            if charCount(v) > r.max then return nil, "'" .. r.key .. "' is longer than " .. r.max end
        elseif r.kind == "number" then
            if type(v) ~= "number" or v ~= v or v == math.huge or v == -math.huge then
                return nil, "'" .. r.key .. "' is not a number"
            end
            local eps = (r.max - r.min) * 1e-4
            if v < r.min - eps or v > r.max + eps then return nil, "'" .. r.key .. "' is out of range" end
            v = math.max(r.min, math.min(r.max, v))
            if r.step then
                local k = (v - r.min) / r.step
                if math.abs(k - math.floor(k + 0.5)) > 1e-3 then return nil, "'" .. r.key .. "' is off the step" end
            end
        elseif r.kind == "option" then
            if type(v) ~= "string" or not r.ids[v] then return nil, "'" .. r.key .. "' is not an option" end
        else
            if v == true or v == 1 or v == "true" then v = true
            elseif v == false or v == 0 or v == "false" then v = false
            else return nil, "'" .. r.key .. "' is not a boolean" end
        end
        values[r.key] = v
    end
    for k in pairs(payload) do
        if not known[k] then return nil, "unknown input '" .. tostring(k) .. "'" end
    end
    return values
end

-- ---------------------------------------------------------------------------
-- Routing
-- ---------------------------------------------------------------------------

mc.on("player.custom_click", function(e)
    local token, n = tostring(e.id):match("^" .. ID_PREFIX .. "(%x+)/(%w+)$")
    if not token then return end
    local player = e.player
    local d = current[player.uuid]
    if not d or d.token ~= token or d.done then return end -- stale or foreign

    if n == "x" then
        finish(d, "escape")
        return
    end
    local action = d.actions[tonumber(n)]
    if not action then
        warn(player.name .. ": click on an unknown dialog button '" .. n .. "'")
        return
    end
    local values, err = validate(d.inputs, e.payload)
    if not values then
        warn(player.name .. ": dialog click dropped, " .. err)
        finish(d, "invalid")
        if d.vanilla ~= "close" then player:closeDialog() end
        return
    end

    -- A button that is not "keep" uses the dialog up, before its handler runs.
    local keep = action.after == "keep"
    if not keep then
        d.done = true
        if current[d.uuid] == d then current[d.uuid] = nil end
    end

    local ok, result
    if action.kind == "command" then
        ok, result = true, nil
        local done, message = player:runCommand(action.command)
        if not done then warn("/" .. action.command .. ": " .. tostring(message)) end
    else
        ok, result = pcall(action.fn, player, values)
    end

    -- Nothing from this library replaced the dialog: clear it.
    if not keep and d.vanilla ~= "close" and current[d.uuid] == nil then player:closeDialog() end
    if not ok then error(result, 0) end
end)

mc.on("player.leave", function(e)
    local d = current[e.player.uuid]
    if d then finish(d, "leave") end
end)

mc.on("uninit", function()
    for _, d in pairs(current) do
        pcall(d.player.closeDialog, d.player)
        finish(d, "reload")
    end
end)

return dialog
