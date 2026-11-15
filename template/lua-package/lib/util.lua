local util = {}

---Builds a greeting for a player name.
---@param name string
---@return string
function util.greet(name)
    return "Hello, " .. tostring(name) .. "! Welcome to PxIgnis."
end

---Formats a short join announcement.
---@param name string
---@return string
function util.join_message(name)
    return "[hello_world] " .. tostring(name) .. " joined the server."
end

return util
