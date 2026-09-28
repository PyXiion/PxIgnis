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

local region -- Region|nil, created by buildRegion() below

local function isReady()
    return region ~= nil and config.lobby ~= nil and #config.spawns > 0
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
    if not isReady() then
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

-- ── The arena region ───────────────────────────────────────────────────────

local function isProtected(player, pos)
    return region ~= nil and region:contains(pos) and not player:hasPermission(ADMIN)
end

mc.on("block.break", function(e)
    if isProtected(e.player, e.pos) then e:cancel() end
end, { priority = "high" })

mc.on("block.place", function(e)
    if isProtected(e.player, e.pos) then e:cancel() end
end, { priority = "high" })

local function buildRegion()
    if region then region:destroy() end
    region = nil
    local a, b = config.pos1, config.pos2
    if not (a and b) then return end

    -- A region is a box from its min corner up to (but not including) its max
    -- corner, so +1 makes the max block part of it.
    region = mc.world(a.world):createRegion(
        vec(math.min(a.x, b.x), math.min(a.y, b.y), math.min(a.z, b.z)),
        vec(math.max(a.x, b.x) + 1, math.max(a.y, b.y) + 1, math.max(a.z, b.z) + 1)
    )
end

buildRegion()

-- ── Commands ───────────────────────────────────────────────────────────────

local function corner(ctx, key)
    local pos = ctx.player.pos
    config[key] = {
        x = math.floor(pos.x), y = math.floor(pos.y), z = math.floor(pos.z),
        world = ctx.player.world.name,
    }
    buildRegion()
    local ready = region and ", the arena region is ready." or "."
    ctx.player:sendMessage("Corner " .. key .. " set" .. ready)
end

register("arena pos1", function(ctx) corner(ctx, "pos1") end, ADMIN)
register("arena pos2", function(ctx) corner(ctx, "pos2") end, ADMIN)

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
