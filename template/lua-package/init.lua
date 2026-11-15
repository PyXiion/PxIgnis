-- hello_world: a minimal PxIgnis Lua package ("hello world" example).
--
-- Copy this folder to: <script_root>/packages/hello_world/
--   e.g. config/ignis/packages/hello_world/
-- Then run /ignis reload.
--
-- This entry module runs eagerly at script load (after dependencies). Its return
-- value is cached and returned by require("pkg:hello_world").

local util = require("lib.util") -- package-local module (lib/util.lua)

local M = {}

---Returns the greeting text for a name.
---@param name string
---@return string
function M.greet(name)
    return util.greet(name)
end

-- Event: greet players when they join.
mc.on("player.join", function(e)
    local name = e.player.name

    -- Private message to the joining player.
    e.player:sendMessage(M.greet(name))

    -- Public announcement.
    mc.broadcast(util.join_message(name))
end)

-- Command: /hello
-- Registered at load time (top-level). Sends a greeting to the caller.
register("hello", function(ctx)
    ctx.player:sendMessage(M.greet(ctx.player.name))
end)

-- Optional: expose a small API to other scripts/packages.
-- local hello = require("pkg:hello_world")
-- hello.greet("Steve")
return M
