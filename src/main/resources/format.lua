local template_cache = {}

local function resolve_dot_path(path)
    local chunks = {}
    for part in string.gmatch(path, "[^%.]+") do
        table.insert(chunks, string.format("[%q]", part))
    end
    return table.concat(chunks)
end

local function compile_expression(expr)
    local dot_expr = expr:match("^%a[%w_.]*$")
    if dot_expr then
        return string.format("tostring(args%s)", resolve_dot_path(dot_expr))
    else
        return string.format("(function(args) local env = setmetatable({}, {__index = args}) return tostring(assert(load('return ' .. %q, 'expr', 't', env))()) end)(args)", expr)
    end
end

local function compile_template(pattern)
    local lua_parts = {}
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
        table.insert(lua_parts, compile_expression(expr))

        last_end = end_pos + 1
    end

    local body = "return function(args) return " .. table.concat(lua_parts, " .. ") .. " end"
    local chunk = assert(load(body, "template", "t"))
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
