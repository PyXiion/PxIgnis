-- config/ignis/arena.lua: a last-player-standing PvP arena.
local async = require "async"
local chestgui = require "core:chestgui"

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
    kills = {},        -- uuid -> kills this round
    timer = 0,         -- seconds left in the current phase
}

local function tell(text)
    for _, player in pairs(game.players) do
        player:sendMessage(text)
    end
end

-- ── Statistics ─────────────────────────────────────────────────────────────

local function stats(player)
    if not player.data.arenaStats then
        player.data.arenaStats = { wins = 0, kills = 0, deaths = 0, games = 0 }
    end
    return player.data.arenaStats
end

-- Wins of every player who ever won, for the leaderboard (offline players too).
mc.data.arenaWins = mc.data.arenaWins or {}
local wins = mc.data.arenaWins

local leaderboard -- Hologram|nil

local function updateLeaderboard()
    if not leaderboard then return end
    local rows = {}
    for _, entry in pairs(wins) do
        rows[#rows + 1] = entry
    end
    table.sort(rows, function(a, b) return a.wins > b.wins end)

    local lines = { "§6§lArena: top players" }
    for i = 1, math.min(5, #rows) do
        lines[#lines + 1] = string.format("§e%d. §f%s §7- %d", i, rows[i].name, rows[i].wins)
    end
    if #rows == 0 then
        lines[#lines + 1] = "§7no winners yet"
    end
    leaderboard.lines = lines
end

local function spawnLeaderboard()
    if leaderboard then leaderboard:destroy() end
    leaderboard = nil
    local point = config.leaderboard
    if not point then return end
    leaderboard = mc.world(point.world):spawnHologram(vec(point.x, point.y, point.z), "Arena")
    updateLeaderboard()
end

-- ── Inventory ──────────────────────────────────────────────────────────────

-- Player inventory slots: 0-35 main, 36-39 armor, 40 offhand.
local LAST_SLOT = 40

-- Saves the player's items in player.data and empties the inventory.
local function stash(player)
    local saved = {}
    for slot = 0, LAST_SLOT do
        local item = player:getItem(slot)
        if item then
            saved[tostring(slot)] = item:serialise()
        end
    end
    player.data.arenaStash = saved
    player:clear()
end

local function restore(player)
    local saved = player.data.arenaStash
    if not saved then return end
    player:clear()
    for slot, json in pairs(saved) do
        player:setItem(tonumber(slot), mc.deserialise("item", json))
    end
    player.data.arenaStash = nil
end

-- ── Kits ───────────────────────────────────────────────────────────────────

local KITS = {
    {
        id = "warrior", name = "§cWarrior", icon = "minecraft:iron_sword",
        items = {
            { "minecraft:iron_sword", 1 }, { "minecraft:shield", 1 }, { "minecraft:cooked_beef", 8 },
        },
        chest = "minecraft:iron_chestplate",
    },
    {
        id = "archer", name = "§aArcher", icon = "minecraft:bow",
        items = {
            { "minecraft:bow", 1 }, { "minecraft:arrow", 32 },
            { "minecraft:stone_sword", 1 }, { "minecraft:cooked_beef", 8 },
        },
        chest = "minecraft:leather_chestplate",
    },
    {
        id = "tank", name = "§9Tank", icon = "minecraft:diamond_chestplate",
        items = {
            { "minecraft:wooden_sword", 1 }, { "minecraft:golden_apple", 2 },
        },
        chest = "minecraft:diamond_chestplate",
    },
}

local function findKit(id)
    for _, kit in ipairs(KITS) do
        if kit.id == id then return kit end
    end
    return KITS[1]
end

local function giveKit(player)
    local kit = findKit(player.data.arenaKit)
    for _, entry in ipairs(kit.items) do
        player:give(entry[1], entry[2])
    end
    player.chest = mc.createItem(kit.chest)
end

local kitMenu = chestgui.create(1, "Choose a kit")
for i, kit in ipairs(KITS) do
    local icon = mc.createItem(kit.icon, { name = kit.name, lore = { "§7Click to choose" } })
    kitMenu:set(1, i * 2 + 1, icon, function(player)
        player.data.arenaKit = kit.id
        player:sendMessage("Kit: " .. kit.name)
        player:playSound("minecraft:ui.button.click")
        return false
    end)
end

-- ── Scoreboard and boss bar ────────────────────────────────────────────────

local bar = mc.createBossBar("Arena", "yellow", "progress")

local function updateUI()
    local status
    if game.phase == "waiting" then
        status = string.format("Waiting for players: %d/%d", count(game.players), MIN_PLAYERS)
        bar.progress = math.min(1, count(game.players) / MIN_PLAYERS)
    elseif game.phase == "countdown" then
        status = string.format("Starting in %d s", game.timer)
        bar.progress = game.timer / COUNTDOWN
    elseif game.phase == "playing" then
        local minutes, seconds = math.floor(game.timer / 60), game.timer % 60
        status = string.format("Alive: %d · %d:%02d", count(game.alive), minutes, seconds)
        bar.progress = game.timer / ROUND_TIME
    else
        status = "Round over"
        bar.progress = 1
    end
    bar.title = status

    for uuid, player in pairs(game.players) do
        local s = stats(player)
        player.sidebar = {
            title = "§6§lArena",
            lines = {
                game.alive[uuid] and "§aYou are fighting" or "§7You are watching",
                "Players: §e" .. count(game.players),
                "",
                "Kills this round: §e" .. (game.kills[uuid] or 0),
                "Wins: §e" .. s.wins,
                "Kit: " .. findKit(player.data.arenaKit).name,
            },
        }
    end
end

local function hideUI(player)
    bar:removePlayer(player)
    player.sidebar = nil
end

-- ── Rounds ─────────────────────────────────────────────────────────────────

local function finish(winner)
    game.phase = "ending"

    if winner then
        stats(winner).wins = stats(winner).wins + 1
        wins[winner.uuid] = { name = winner.name, wins = stats(winner).wins }
        updateLeaderboard()
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
            restore(player)
            teleport(player, config.lobby)
        end
        game.alive = {}
        game.phase = "waiting"

        -- Tell other scripts, now that the winner has their own items back.
        if winner and game.players[winner.uuid] then
            mc.emit("arena:win", winner, game.kills[winner.uuid] or 0)
        end
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
    stats(player).deaths = stats(player).deaths + 1
    restore(player)
    teleport(player, config.lobby)

    if killer and game.alive[killer.uuid] then
        game.kills[killer.uuid] = (game.kills[killer.uuid] or 0) + 1
        stats(killer).kills = stats(killer).kills + 1
        tell("§c" .. player.name .. " §7was eliminated by §a" .. killer.name)
    else
        tell("§c" .. player.name .. " §7was eliminated")
    end
    checkWinner()
end

local function startRound()
    if not isReady() then
        game.phase = "waiting"
        tell("§cThe arena setup is incomplete, the round cannot start.")
        return
    end
    game.phase = "playing"
    game.timer = ROUND_TIME
    game.alive = {}
    game.kills = {}

    local i = 0
    for uuid, player in pairs(game.players) do
        i = i + 1
        local spawn = config.spawns[(i - 1) % #config.spawns + 1]
        game.alive[uuid] = true
        stash(player)
        giveKit(player)
        player.health = player.maxHealth
        player.food = 20
        teleport(player, spawn)
        stats(player).games = stats(player).games + 1
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
    updateUI()
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
        bar:addPlayer(player)
        teleport(player, config.lobby)
        tell("§a" .. player.name .. " §7joined the arena (" .. count(game.players) .. ")")
        updateUI()
    end
end

local function leave(player)
    local uuid = player.uuid
    if not game.players[uuid] then return end
    if game.alive[uuid] then
        eliminate(player, nil)
    end
    game.players[uuid] = nil
    game.kills[uuid] = nil
    hideUI(player)
    player:sendMessage("§7You left the arena.")
end

mc.on("player.leave", function(e)
    leave(e.player)
end)

-- Came back after a crash in the middle of a round: return their items.
mc.on("player.join", function(e)
    if e.player.data.arenaStash then
        restore(e.player)
        if config.lobby then teleport(e.player, config.lobby) end
        e.player:sendMessage("§eYour items from the last arena round were returned.")
    end
end)

-- Before a reload the game state is thrown away: put everyone back first.
mc.on("uninit", function()
    for uuid, player in pairs(game.players) do
        if game.alive[uuid] then
            restore(player)
            teleport(player, config.lobby)
        end
        player.sidebar = nil
        player:sendMessage("§eThe arena was reloaded, join again with /arena join.")
    end
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
spawnLeaderboard()

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

register("arena setleaderboard", function(ctx)
    config.leaderboard = here(ctx.player)
    spawnLeaderboard()
    ctx.player:sendMessage("Leaderboard placed.")
end, ADMIN)

register("arena join", function(ctx) join(ctx.player) end)
register("arena leave", function(ctx) leave(ctx.player) end)
register("arena kit", function(ctx) kitMenu:open(ctx.player) end)

register("arena stats [<target:player>]", function(ctx, target)
    local player = target or ctx.player
    local s = stats(player)
    ctx.player:sendMessage(string.format(
        "§6%s§7: %d wins, %d kills, %d deaths in %d games",
        player.name, s.wins, s.kills, s.deaths, s.games))
end)
