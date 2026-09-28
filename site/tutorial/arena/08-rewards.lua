-- config/ignis/arena_rewards.lua: rewards for arena winners.
local async = require "async"

-- A Discord webhook URL to announce winners there, or nil to skip it.
local WEBHOOK = nil

mc.on("arena:win", function(winner, kills)
    winner:give("minecraft:diamond", 1 + kills)
    mc.broadcast("§6" .. winner.name .. " §ewon an arena round with " .. kills .. " kills!")

    if WEBHOOK then
        local response = async.fetch({
            url = WEBHOOK,
            method = "POST",
            json = { content = winner.name .. " won an arena round with " .. kills .. " kills" },
        })
        if not response.ok then
            print("arena webhook failed: " .. tostring(response.error or response.status))
        end
    end
end)
