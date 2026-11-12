-- config/ignis/arena.lua: a last-player-standing PvP arena.

local ADMIN = "arena.admin"

-- Corners, spawn points and lobby, kept across restarts by mc.data.
mc.data.arena = mc.data.arena or {}
local config = mc.data.arena
config.spawns = config.spawns or {}

-- Where a player stands, as a plain table that can be saved.
local function here(player)
    local p = player.pos
    return { x = p.x, y = p.y, z = p.z, world = player.world.name }
end

local function teleport(player, point)
    player:teleport(point.x, point.y, point.z, point.world)
end

local function count(t)
    local n = 0
    for _ in pairs(t) do n = n + 1 end
    return n
end

-- Game state. It lives only in memory: a reload starts from scratch.
local game = {
    players = {}, -- uuid -> Player: everyone who joined
}

local function tell(text)
    for _, player in pairs(game.players) do
        player:sendMessage(text)
    end
end

-- ── Joining and leaving ────────────────────────────────────────────────────

local function join(player)
    if not config.lobby then
        player:sendMessage("§cThe arena is not set up yet.")
    elseif game.players[player.uuid] then
        player:sendMessage("§eYou have already joined.")
    else
        game.players[player.uuid] = player
        teleport(player, config.lobby)
        tell("§a" .. player.name .. " §7joined the arena (" .. count(game.players) .. ")")
    end
end

local function leave(player)
    local uuid = player.uuid
    if not game.players[uuid] then return end
    game.players[uuid] = nil
    player:sendMessage("§7You left the arena.")
end

mc.on("player.leave", function(e)
    leave(e.player)
end)

-- ── Commands ───────────────────────────────────────────────────────────────

register("arena addspawn", function(ctx)
    local spawns = config.spawns
    spawns[#spawns + 1] = here(ctx.player)
    ctx.player:sendMessage("Spawn point #" .. #spawns .. " added.")
end, ADMIN)

register("arena clearspawns", function(ctx)
    config.spawns = {}
    ctx.player:sendMessage("All spawn points removed.")
end, ADMIN)

register("arena setlobby", function(ctx)
    config.lobby = here(ctx.player)
    ctx.player:sendMessage("Lobby set.")
end, ADMIN)

register("arena join", function(ctx) join(ctx.player) end)
register("arena leave", function(ctx) leave(ctx.player) end)
