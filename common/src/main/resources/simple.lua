local format = require("core:format")

local simple = {}

simple.defaultOverlay = 20 * 7

function simple.register(syntax, template, range, overlay)
    if type(syntax) ~= "string" or #syntax == 0 then
        error("simple.register: syntax must be a non-empty string", 2)
    end
    if type(template) ~= "string" or #template == 0 then
        error("simple.register: template must be a non-empty string", 2)
    end
    if range ~= nil and type(range) ~= "number" then
        error("simple.register: range must be a number or nil", 2)
    end
    if overlay ~= nil and type(overlay) ~= "boolean" and type(overlay) ~= "number" then
        error("simple.register: overlay must be boolean, number, or nil", 2)
    end

    local argNames = {}
    for arg in syntax:gmatch("<([^:>]+):[^>]+>") do
        if arg == "p" then
            error("simple.register: argument name 'p' is reserved for ctx.player", 2)
        end
        table.insert(argNames, arg)
    end

    if #argNames == 0 then
        error("simple.register: syntax must contain at least one <name:type> argument", 2)
    end

    overlay = overlay == true and simple.defaultOverlay or overlay

    local render = format(template)

    local handler = function(ctx, ...)
        local argValues = {...}
        local argTable = {p = ctx.player}
        for i, name in ipairs(argNames) do
            argTable[name] = argValues[i]
        end

        if range ~= nil and range > 0 then
            local pos = ctx.player.pos
            ctx.player.world:broadcastInRange(render(argTable), pos.x, pos.y, pos.z, range, overlay)
        else
            mc.broadcast(render(argTable), overlay)
        end
    end

    register(syntax, handler)
end

return simple
