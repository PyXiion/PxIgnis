-- config/ignis/arena.lua: a last-player-standing PvP arena.
local async = require "async"

local ADMIN = "arena.admin"
local MIN_PLAYERS = 2
local COUNTDOWN = 10   -- seconds before a round starts
local ROUND_TIME = 300 -- seconds before a round ends in a draw

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
    phase = "waiting", -- waiting -> countdown -> playing -> ending
    players = {},      -- uuid -> Player: everyone who joined
    alive = {},        -- uuid -> true: still fighting (playing phase only)
    timer = 0,         -- seconds left in the current phase
}

local function tell(text)
    for _, player in pairs(game.players) do
        player:sendMessage(text)
    end
end

-- ── Rounds ─────────────────────────────────────────────────────────────────

local function finish(winner)
    game.phase = "ending"

    if winner then
        for _, player in pairs(game.players) do
            player:sendTitle("§6" .. winner.name, "wins the round!")
        end
    else
        tell("§eThe round ended in a draw.")
    end

    -- Celebrate for five seconds, then send everyone back to the lobby.
    mc.schedule(0, function()
        for _ = 1, 5 do
            if winner and game.alive[winner.uuid] then
                local pos = winner.pos + vec(0, 1, 0)
                winner.world:particle("minecraft:totem_of_undying", pos, { count = 40, spread = 0.5 })
                winner.world:playSound("minecraft:entity.firework_rocket.launch", winner.pos)
            end
            async.sleep(20)
        end
        for uuid in pairs(game.alive) do
            local player = game.players[uuid]
            teleport(player, config.lobby)
        end
        game.alive = {}
        game.phase = "waiting"
    end)
end

local function checkWinner()
    if game.phase ~= "playing" then return end
    local left = count(game.alive)
    if left == 1 then
        finish(game.players[next(game.alive)])
    elseif left == 0 then
        finish(nil)
    end
end

local function eliminate(player, killer)
    game.alive[player.uuid] = nil
    teleport(player, config.lobby)

    if killer and game.alive[killer.uuid] then
        tell("§c" .. player.name .. " §7was eliminated by §a" .. killer.name)
    else
        tell("§c" .. player.name .. " §7was eliminated")
    end
    checkWinner()
end

local function startRound()
    game.phase = "playing"
    game.timer = ROUND_TIME
    game.alive = {}

    local i = 0
    for uuid, player in pairs(game.players) do
        i = i + 1
        local spawn = config.spawns[(i - 1) % #config.spawns + 1]
        game.alive[uuid] = true
        player.health = player.maxHealth
        player.food = 20
        teleport(player, spawn)
    end
    tell("§aFight!")
end

-- Runs once per second and moves the game from phase to phase.
local function tick()
    if game.phase == "waiting" then
        if count(game.players) >= MIN_PLAYERS then
            game.phase = "countdown"
            game.timer = COUNTDOWN
        end
    elseif game.phase == "countdown" then
        if count(game.players) < MIN_PLAYERS then
            game.phase = "waiting"
            tell("§eNot enough players, the countdown stopped.")
        else
            game.timer = game.timer - 1
            if game.timer <= 0 then
                startRound()
            elseif game.timer <= 3 then
                tell("§e" .. game.timer .. "...")
            end
        end
    elseif game.phase == "playing" then
        game.timer = game.timer - 1
        if game.timer <= 0 then finish(nil) end
    end
end

mc.scheduleRepeating(20, 20, tick)

-- ── Joining and leaving ────────────────────────────────────────────────────

local function join(player)
    if not isReady() then
        player:sendMessage("§cThe arena is not set up yet.")
    elseif game.players[player.uuid] then
        player:sendMessage("§eYou have already joined.")
    elseif game.phase == "playing" or game.phase == "ending" then
        player:sendMessage("§eA round is in progress, try again in a moment.")
    else
        game.players[player.uuid] = player
        teleport(player, config.lobby)
        tell("§a" .. player.name .. " §7joined the arena (" .. count(game.players) .. ")")
    end
end

local function leave(player)
    local uuid = player.uuid
    if not game.players[uuid] then return end
    if game.alive[uuid] then
        eliminate(player, nil)
    end
    game.players[uuid] = nil
    player:sendMessage("§7You left the arena.")
end

mc.on("player.leave", function(e)
    leave(e.player)
end)

-- ── Fights ─────────────────────────────────────────────────────────────────

-- A fatal hit knocks the player out of the round instead of killing them.
mc.on("entity.death", function(e)
    local player = e.player
    if not player or not game.alive[player.uuid] then return end
    e:cancel()
    player.health = player.maxHealth
    player.fireTicks = 0
    if game.phase == "playing" then
        eliminate(player, e.attacker and game.players[e.attacker.uuid])
    end
end, { priority = "high" })

-- No friendly hits before the round starts.
mc.on("player.attack", function(e)
    if game.players[e.player.uuid] and not game.alive[e.player.uuid] then
        e:cancel()
    end
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

    -- Walking (or being knocked) out of the arena counts as losing.
    region:on("leave", function(e)
        if e.player and game.phase == "playing" and game.alive[e.player.uuid] then
            e.player:sendMessage("§cYou left the arena!")
            eliminate(e.player, nil)
        end
    end)

    -- Nobody else walks in during a round.
    region:on("enter", function(e)
        local player = e.player
        if player and game.phase == "playing" and not game.alive[player.uuid]
            and not player:hasPermission(ADMIN) then
            teleport(player, config.lobby)
            player:sendMessage("§eA round is in progress.")
        end
    end)
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
