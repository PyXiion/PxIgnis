local template_cache = {}

local function resolve_dot_path(path)
    local chunks = {}
    for part in string.gmatch(path, "[^%.]+") do
        table.insert(chunks, string.format("[%q]", part))
    end
    return table.concat(chunks)
end

-- Returns Lua source for one `{expr}`. A plain dotted path is inlined; anything else is compiled once, here, into a
-- function stored in `exprs`, so rendering never calls `load`.
local function compile_expression(expr, exprs)
    local dot_expr = expr:match("^%a[%w_.]*$")
    if dot_expr then
        return string.format("tostring(args%s)", resolve_dot_path(dot_expr))
    end

    local current_args
    local env = setmetatable({}, {__index = function(_, key) return current_args[key] end})
    local chunk = assert(load("return " .. expr, "expr", "t", env))
    exprs[#exprs + 1] = function(args)
        current_args = args
        return tostring(chunk())
    end
    return string.format("exprs[%d](args)", #exprs)
end

local function compile_template(pattern)
    local lua_parts = {}
    local exprs = {}
    local last_end = 1

    while true do
        local start_pos, end_pos = string.find(pattern, "{(.-)}", last_end)
        if not start_pos then
            local text = string.sub(pattern, last_end)
            if #text > 0 then
                table.insert(lua_parts, string.format("%q", text))
            end
            break
        end

        local text = string.sub(pattern, last_end, start_pos - 1)
        if #text > 0 then
            table.insert(lua_parts, string.format("%q", text))
        end

        local expr = string.sub(pattern, start_pos + 1, end_pos - 1)
        table.insert(lua_parts, compile_expression(expr, exprs))

        last_end = end_pos + 1
    end

    local body = "return function(args) return " .. table.concat(lua_parts, " .. ") .. " end"
    local chunk = assert(load(body, "template", "t", {tostring = tostring, exprs = exprs}))
    return chunk()
end

local format = setmetatable({}, {
    __call = function(_, pattern)
        local fn = template_cache[pattern]
        if not fn then
            fn = compile_template(pattern)
            template_cache[pattern] = fn
        end
        return fn
    end
})

function format.broadcast(template)
    return function(args)
        mc.broadcast(format(template)(args))
    end
end

return format
