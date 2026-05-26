-- ==========================================================================
-- demo.lua — Practical Lua patterns for PxRP
-- ==========================================================================
-- Usage:  Place this file in the config/pxrp/ directory.
--         On first run it is copied there automatically.
--
-- This file demonstrates useful scripting patterns, not just API methods.
-- Each section shows a different concept you can use in your own scripts.
-- All messages use § color codes for visual clarity (works in Minecraft chat).
--
-- Contents:
--   1. Private messaging  — cross-player direct messages
--   2. Home system        — persisted named locations per player
--   3. Teleport requests  — time-limited cross-player state
--   4. Mute / moderation  — command + event integration
--   5. Global config      — data-driven runtime settings
--   6. Report cooldown    — per-player throttle + notifications
--   7. New arg types      — int, double, bool, block_pos
--   8. Choice & optional  — choice=... syntax and [name:type] optional args
--   9. Player info        — reading aggregate entity data
--  10. Item & inventory   — kits, hats, rename, repair, invsee
--  11. World & entity     — spawn, tags, time, weather, particle, broadcastInRange
--  12. Scheduler          — mc.schedule, mc.scheduleRepeating, mc.cancelTask
-- ==========================================================================


-- ==========================================================================
-- Pattern 1: Private messaging
-- ==========================================================================
-- /msg <player> <message>
--
-- Uses the :name override syntax to differentiate two same-type args.
-- Sends a formatted whisper to the target via player:sendMessage(),
-- and confirms delivery to the sender.

function msgHandler(ctx, target, msg)
    local sender = ctx.player

    target:sendMessage("§7[§d" .. sender.name .. " §r->§dyou§7]§r " .. msg)
    sender:sendMessage("§7[§dyou §r->§d" .. target.name .. "§7]§r " .. msg)
end


-- ==========================================================================
-- Pattern 2: Home system — nested data with explicit reassignment
-- ==========================================================================
-- /sethome <name>   — save current position as a named home
-- /home <name>      — teleport to a saved home
-- /homelist         — list all saved homes
--
-- KEY PATTERN: Nested table reassignment.
-- DataTable does NOT track deep key changes. Writing to
--   player.data.homes[name] = {...}
-- silently fails to persist. You MUST:
--   1. Read the whole nested table:  local homes = player.data.homes or {}
--   2. Modify it:                    homes[name] = {...}
--   3. Write it back:                player.data.homes = homes

function sethomeHandler(ctx, name)
    local player = ctx.player
    local pos = player.pos
    local homes = player.data.homes or {}

    homes[name] = {
        x     = pos.x,
        y     = pos.y,
        z     = pos.z,
        world = player.world.name
    }

    -- Explicit re-assignment — required for nested data
    player.data.homes = homes

    player:sendMessage("§aHome §f'" .. name .. "' §aset!")
end

function homeHandler(ctx, name)
    local player = ctx.player
    local homes = player.data.homes or {}
    local home  = homes[name]

    if not home then
        player:sendMessage("§cHome §f'" .. name .. "'§c not found. Use §f/sethome " .. name)
        return
    end

    player:teleport(home.x, home.y, home.z, home.world)
    player:sendMessage("§aTeleported to §f'" .. name .. "'§a!")
end

function homelistHandler(ctx)
    local player = ctx.player
    local homes  = player.data.homes or {}
    local names  = {}

    for name, _ in pairs(homes) do
        table.insert(names, name)
    end

    if #names == 0 then
        player:sendMessage("§cYou have no saved homes. Use §f/sethome <name>")
        return
    end

    table.sort(names)
    player:sendMessage("§aYour homes: §f" .. table.concat(names, "§7, §f"))
end


-- ==========================================================================
-- Pattern 3: Teleport requests — time-limited state via global data
-- ==========================================================================
-- /tpa <player>       — request to teleport to that player
-- /tpaccept <player>  — accept (teleports the requester to you)
-- /tpdeny <player>    — deny (cleans up the request)
--
-- KEY PATTERNS:
--   - Cross-player communication: both players are available as Player
--     objects because every command uses a {target} argument.
--     The executor is ctx.player; the argument is the other player.
--   - Time-limited state: pending requests carry a timestamp and
--     are rejected after TPA_TIMEOUT seconds.
--   - Global data as a lightweight request bus.

local TPA_TIMEOUT = 30

function tpaHandler(ctx, target)
    local player = ctx.player
    local now    = mc.time()

    local requests       = mc.data.tpaRequests or {}
    requests[player.name] = { target = target.name, time = now }
    mc.data.tpaRequests   = requests

    target:sendMessage(
        "§e" .. player.name .. " §awants to teleport to you!\n"
        .. "  §a/tpaccept " .. player.name .. "  §7|  §c/tpdeny " .. player.name
    )
    player:sendMessage("§aTeleport request sent to §f" .. target.name)
end

function tpacceptHandler(ctx, sender)
    local player  = ctx.player
    local requests = mc.data.tpaRequests or {}
    local request  = requests[sender.name]
    local now      = mc.time()

    if not request or request.target ~= player.name then
        player:sendMessage("§cNo pending request from §f" .. sender.name)
        return
    end

    if now - request.time > TPA_TIMEOUT then
        requests[sender.name] = nil
        mc.data.tpaRequests  = requests
        player:sendMessage("§cTeleport request from §f" .. sender.name .. "§c expired.")
        return
    end

    local pos = player.pos
    sender:teleport(pos.x, pos.y, pos.z, player.world)

    requests[sender.name] = nil
    mc.data.tpaRequests  = requests

    player:sendMessage("§aTeleported §f" .. sender.name .. "§a to you.")
    sender:sendMessage("§aTeleport request accepted!")
end

function tpdenyHandler(ctx, sender)
    local player  = ctx.player
    local requests = mc.data.tpaRequests or {}
    local request  = requests[sender.name]

    if not request or request.target ~= player.name then
        player:sendMessage("§cNo pending request from §f" .. sender.name)
        return
    end

    requests[sender.name] = nil
    mc.data.tpaRequests  = requests

    sender:sendMessage("§c" .. player.name .. " denied your teleport request.")
    player:sendMessage("§cTeleport request from §f" .. sender.name .. "§c denied.")
end


-- ==========================================================================
-- Pattern 4: Mute / moderation — command + event integration
-- ==========================================================================
-- /mute <player>     — prevent a player from chatting
-- /unmute <player>   — restore chat access
--
-- KEY PATTERN: Command sets state in per-player data; an event handler
-- reads that state to block the action. This is the fundamental pattern
-- for most moderation features (mute, jail, freeze, etc.).

function muteHandler(ctx, target)
    target.data.muted = true
    ctx.player:sendMessage("§cMuted §f" .. target.name)
    target:sendMessage("§cYou have been muted by a moderator.")
end

function unmuteHandler(ctx, target)
    target.data.muted = false
    ctx.player:sendMessage("§aUnmuted §f" .. target.name)
    target:sendMessage("§aYou have been unmuted.")
end


-- ==========================================================================
-- Pattern 5: Global config — data-driven runtime settings
-- ==========================================================================
-- /setwelcome <message>  — set a welcome message for joining players
-- /setspawn              — save the current position as server spawn
--
-- KEY PATTERN: Use mc.data as a runtime configuration store.
-- Commands write to it; events read from it — no reload needed.

function setwelcomeHandler(ctx, msg)
    mc.data.welcomeMessage = msg
    ctx.player:sendMessage("§aWelcome message set!")
end

function setspawnHandler(ctx)
    local player = ctx.player
    mc.data.spawn = {
        x     = player.pos.x,
        y     = player.pos.y,
        z     = player.pos.z,
        world = player.world.name
    }
    ctx.player:sendMessage("§aSpawn point set!")
end


-- ==========================================================================
-- Pattern 6: Report — cooldown + targeted notification
-- ==========================================================================
-- /report <message>   — send a report to staff
--
-- KEY PATTERNS:
--   - Per-player cooldown stored in data with timestamp check
--   - Descriptive feedback showing remaining time

local REPORT_COOLDOWN = 60

function reportHandler(ctx, msg)
    local player    = ctx.player
    local now       = mc.time()
    local lastReport = player.data.lastReport or 0

    if now - lastReport < REPORT_COOLDOWN then
        local remaining = math.ceil(REPORT_COOLDOWN - (now - lastReport))
        player:sendMessage("§cYou can report again in §f" .. remaining .. "§c seconds.")
        return
    end

    player.data.lastReport = now

    local formatted = "§4[Report] §e" .. player.name .. "§7: §f" .. msg
    mc.broadcast(formatted)
    player:sendMessage("§aReport sent. Staff have been notified.")
end


-- ==========================================================================
-- Pattern 7: New argument types — int, double, bool, block_pos
-- ==========================================================================
-- /math <a:int> <op:word> <b:double>  — simple in-game calculator
-- /setwarp <name:text> <pos:block_pos> — using block_pos for coordinates
--
-- KEY PATTERNS:
--   - int, double, float produce native Lua numbers
--   - bool produces a Lua boolean (true/false)
--   - block_pos returns {x, y, z} — works like stored position tables

function mathHandler(ctx, a, op, b)
    local result
    if op == "+" then
        result = a + b
    elseif op == "-" then
        result = a - b
    elseif op == "*" then
        result = a * b
    elseif op == "/" then
        if b == 0 then return ctx.player:sendMessage("§cCannot divide by zero!") end
        result = a / b
    else
        ctx.player:sendMessage("§cUnknown operator. Use +, -, *, /")
        return
    end
    ctx.player:sendMessage("§aResult: §f" .. string.format("%.2f", result))
end

function setwarpHandler(ctx, name, pos)
    local warps = mc.data.warps or {}
    warps[name] = { x = pos.x, y = pos.y, z = pos.z }
    mc.data.warps = warps
    ctx.player:sendMessage("§aWarp §f'" .. name .. "' §aset at " .. pos.x .. ", " .. pos.y .. ", " .. pos.z)
end


-- ==========================================================================
-- Pattern 8: Choice type + optional arguments
-- ==========================================================================
-- /gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]
-- /kick <target:player> [<reason:text>]
--
-- KEY PATTERNS:
--   - choice type shows tab completions and validates at runtime
--   - [name:type] defines an optional trailing argument (nil when omitted)
--   - Optional args generate multiple Brigadier trees automatically
--   - The handler just checks "if reason then ... end" — Lua passes nil

function gamemodeHandler(ctx, mode, target)
    local p = target or ctx.player
    local old = p.gamemode
    p.gamemode = mode
    ctx.player:sendMessage("§aSet " .. p.name .. "'s gamemode from §f" .. old .. " §ato §f" .. mode)
end

function kickHandler(ctx, target, reason)
    target:kick(reason or "Kicked by staff")
    ctx.player:sendMessage("§aKicked §f" .. target.name)
end


-- ==========================================================================
-- Pattern 9: Player info — reading aggregate entity data
-- ==========================================================================
-- /whois <player>   — show detailed info about a player
--
-- KEY PATTERN: Access multiple ctx.player properties in a single
-- command to build a rich information display.

function whoisHandler(ctx, target)
    local p   = target
    local pos = p.pos

    ctx.player:sendMessage(
        "§6--- §e" .. p.name .. " §6---\n"
        .. "§7UUID:     §f" .. p.uuid .. "\n"
        .. "§7World:    §f" .. p.world.name .. "\n"
        .. "§7Position: §f" .. string.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z) .. "\n"
        .. "§7Health:   §f" .. string.format("%.1f", p.health) .. "§7/§f" .. string.format("%.1f", p.maxHealth) .. "\n"
        .. "§7Food:     §f" .. p.food .. "\n"
        .. "§7Gamemode: §f" .. p.gamemode .. "\n"
        .. "§7Ping:     §f" .. p.ping .. "ms\n"
        .. "§7XP:       §fLvl " .. p.xpLevel .. " §7(§f" .. string.format("%.0f", p.xpProgress * 100) .. "%§7)\n"
        .. "§7OP:       §f" .. tostring(p.isOp)
    )
end


-- ==========================================================================
-- Pattern 10: Item & inventory manipulation
-- ==========================================================================
-- /kit <name:choice=warrior,archer,miner>  — gives a predefined kit
-- /hat                                    — puts main-hand item on head
-- /rename <name:text>                     — renames the item in main hand
-- /repair                                — removes damage from main-hand item
-- /cleararmor                            — clears all armor slots
-- /invsee <target:player>                — shows target's hotbar
--
-- KEY PATTERNS:
--   - mc.createItem builds items with components
--   - player:getItem(slot) / player:setItem(slot, item) read/write any slot
--   - player.mainhand / player.offhand / player.head / player.chest / player.legs / player.feet
--     are property-style shortcuts for the active hotbar slot, offhand, and armor slots
--   - player:give accepts both string IDs and ItemStack objects
--   - ItemStack objects must come from mc.createItem; raw strings/ints fail in setItem

local KITS = {
    warrior = {
        mc.createItem("minecraft:iron_sword",     { name = "§bWarrior Blade",     unbreakable = true }),
        mc.createItem("minecraft:shield",          { name = "§bWarrior Shield",   unbreakable = true }),
        mc.createItem("minecraft:iron_helmet",     { name = "§bWarrior Helm",     unbreakable = true }),
        mc.createItem("minecraft:iron_chestplate", { name = "§bWarrior Plate",    unbreakable = true }),
        mc.createItem("minecraft:iron_leggings",   { name = "§bWarrior Greaves",  unbreakable = true }),
        mc.createItem("minecraft:iron_boots",      { name = "§bWarrior Boots",    unbreakable = true }),
        mc.createItem("minecraft:cooked_beef", 16),
    },
    archer = {
        mc.createItem("minecraft:bow",              { name = "§aLongbow",         unbreakable = true }),
        mc.createItem("minecraft:arrow", 64),
        mc.createItem("minecraft:arrow", 64),
        mc.createItem("minecraft:leather_helmet",   { name = "§aArcher Cap",      custom_model_data = 1 }),
        mc.createItem("minecraft:leather_chestplate", { name = "§aArcher Tunic",  custom_model_data = 1 }),
        mc.createItem("minecraft:golden_boots",     { name = "§aSwift Boots" }),
    },
    miner = {
        mc.createItem("minecraft:iron_pickaxe",     { name = "§7Miner's Pick",    unbreakable = true }),
        mc.createItem("minecraft:iron_shovel",      { name = "§7Miner's Shovel",  unbreakable = true }),
        mc.createItem("minecraft:torch", 64),
        mc.createItem("minecraft:torch", 64),
        mc.createItem("minecraft:cooked_porkchop", 32),
    },
}

function kitHandler(ctx, name)
    local items = KITS[name]
    if not items then
        ctx.player:sendMessage("§cUnknown kit.")
        return
    end
    ctx.player:clear()
    for _, item in ipairs(items) do
        ctx.player:give(item)
    end
    ctx.player:sendMessage("§aKit §f" .. name .. " §agiven!")
end

function hatHandler(ctx)
    local held = ctx.player.mainhand
    if not held then
        ctx.player:sendMessage("§cYou must hold an item in your main hand.")
        return
    end
    local helmet = ctx.player.head
    ctx.player.head = held
    ctx.player.mainhand = helmet
    ctx.player:sendMessage("§aNice hat!")
end

function renameHandler(ctx, name)
    local held = ctx.player.mainhand
    if not held then
        ctx.player:sendMessage("§cYou must hold an item in your main hand.")
        return
    end
    local renamed = mc.createItem(held.id, {
        count = held.count,
        name = name,
    })
    ctx.player.mainhand = renamed
    ctx.player:sendMessage("§aItem renamed to §f" .. name)
end

function repairHandler(ctx)
    local held = ctx.player.mainhand
    if not held then
        ctx.player:sendMessage("§cYou must hold an item in your main hand.")
        return
    end
    local repaired = mc.createItem(held.id, {
        count = held.count,
        unbreakable = true,
    })
    ctx.player.mainhand = repaired
    ctx.player:sendMessage("§aItem repaired!")
end

function cleararmorHandler(ctx)
    ctx.player.head = nil
    ctx.player.chest = nil
    ctx.player.legs = nil
    ctx.player.feet = nil
    ctx.player:sendMessage("§aArmor cleared!")
end

function invseeHandler(ctx, target)
    local lines = {}
    for slot = 0, 8 do
        local item = target:getItem(slot)
        if item then
            table.insert(lines, "§7[" .. slot .. "] §f" .. item.id .. " §7x" .. item.count)
        else
            table.insert(lines, "§7[" .. slot .. "] §8empty")
        end
    end
    ctx.player:sendMessage("§6--- " .. target.name .. "'s hotbar ---")
    ctx.player:sendMessage(table.concat(lines, "\n"))
end


-- ==========================================================================
-- Pattern 11: World & entity API
-- ==========================================================================
-- /spawnmob <entity:text>                — spawn an entity with custom name + equipment
-- /tagself <tag:text>                    — add a command tag to yourself
-- /worldinfo                             — show world time, weather, name
-- /settime <time:choice=day,night,noon,midnight> — change world time
-- /yell <msg:text>                       — broadcast within 50 blocks
--
-- KEY PATTERNS:
--   - player.world returns a World wrapper (name, time, raining, thundering)
--   - world:spawn(id, pos, {overrides}) creates and returns an EntityWrapper
--   - Spawned entities have full property access (health, type, uuid, tags, equipment)
--   - entity.tags is a boolean proxy table backed by command tags
--   - Setting equipment on spawned entities uses equipStack internally
--   - world:particle(id, x, y, z) spawns particles visible to all players in that world

function spawnmobHandler(ctx, entityId)
    local player = ctx.player
    local world  = player.world
    local pos    = player.pos

    -- Spawn 3 blocks in front of the player
    local dir = player.dir
    local offset = { x = pos.x + dir.x * 3, y = pos.y + 1, z = pos.z + dir.z * 3 }

    local mob = world:spawn(entityId, offset, {
        custom_name = "§e" .. player.name .. "'s pet",
        health = 40,
    })

    if not mob then
        player:sendMessage("§cFailed to spawn '" .. entityId .. "'")
        return
    end

    mob.head = mc.createItem("minecraft:diamond_helmet", { name = "§bCrown", unbreakable = true })
    mob.mainhand = mc.createItem("minecraft:iron_sword", { name = "§bGuardian Blade", unbreakable = true })

    player:sendMessage(
        "§aSpawned §f" .. mob.type .. " §a(§f" .. string.format("%.0f", mob.health)
        .. "§a HP, UUID: §f" .. mob.uuid .. "§a)"
    )
end

function tagselfHandler(ctx, tag)
    local player = ctx.player
    player.tags[tag] = true

    local tags = {}
    for t, _ in pairs(player.tags) do
        table.insert(tags, t)
    end
    table.sort(tags)

    player:sendMessage("§aTag §f'" .. tag .. "'§a added. Tags: §f" .. table.concat(tags, "§7, §f"))
end

function worldinfoHandler(ctx)
    local w = ctx.player.world

    local period
    local t = w.time % 24000
    if t < 12000 then
        period = "§eDay"
    elseif t < 14000 then
        period = "§6Sunset"
    else
        period = "§3Night"
    end

    ctx.player:sendMessage(
        "§6--- World: §e" .. w.name .. " §6---\n"
        .. "§7Time:     §f" .. math.floor(w.time) .. " §7(" .. period .. "§7)\n"
        .. "§7Raining:  §f" .. tostring(w.raining) .. "\n"
        .. "§7Thunder:  §f" .. tostring(w.thundering)
    )
end

function settimeHandler(ctx, period)
    local w = ctx.player.world

    local offsets = {
        day      = 1000,
        noon     = 6000,
        night    = 13000,
        midnight = 18000,
    }

    w.time = w.time - (w.time % 24000) + (offsets[period] or 1000)
    ctx.player:sendMessage("§aTime set to §f" .. period .. "§a.")
end

function yellHandler(ctx, msg)
    local player = ctx.player
    local pos = player.pos
    player.world:broadcastInRange(msg, pos.x, pos.y, pos.z, 50)
    player:sendMessage("§aYelled within 50 blocks.")
end


-- ==========================================================================
-- Pattern 12: Scheduler — delayed and repeating tasks
-- ==========================================================================
-- ==========================================================================
-- /boom <target:player>          — target explodes after 5 seconds
-- /countdown <seconds:int>       — starts a visible countdown (repeating)
-- /cancelcountdown               — cancels an active countdown
--
-- KEY PATTERNS:
--   - mc.schedule(delay, callback) fires a function once after delay ticks
--   - mc.scheduleRepeating(delay, interval, callback) fires repeatedly
--   - mc.cancelTask(id) stops a pending or repeating task
--   - Always capture the task id and check it before cancel
--   - 20 ticks = 1 second
--   - Callbacks are closures — they capture variables at creation time

local countdowns = {}

function boomHandler(ctx, target)
    local sender = ctx.player
    sender:sendMessage("§7Fuse lit on §f" .. target.name .. "§7...")
    target:sendMessage("§cYou have 5 seconds to run!")

    mc.schedule(100, function()
        target.world:spawn("minecraft:creeper", target.pos, {})
        target:sendMessage("§c§kBOOM§r §cYou exploded!")
        sender:sendMessage("§a" .. target.name .. " went boom!")
    end)
end

function countdownHandler(ctx, seconds)
    local player = ctx.player

    -- Cancel any existing countdown for this player
    local oldId = countdowns[player.name]
    if oldId then
        mc.cancelTask(oldId)
        countdowns[player.name] = nil
    end

    local remaining = seconds

    -- Initial message
    player:sendMessage("§6Countdown: §f" .. remaining .. "§6s")

    local id
    id = mc.scheduleRepeating(20, 20, function()
        remaining = remaining - 1
        if remaining <= 0 then
            player:sendMessage("§a§lGO!")
            mc.cancelTask(id)
            countdowns[player.name] = nil
        else
            player:sendMessage("§6Countdown: §f" .. remaining .. "§6s")
        end
    end)

    countdowns[player.name] = id
    player:sendMessage("§7Task ID: §f" .. id)
end

function cancelcountdownHandler(ctx)
    local player = ctx.player
    local id = countdowns[player.name]

    if not id then
        player:sendMessage("§cYou have no active countdown.")
        return
    end

    if mc.cancelTask(id) then
        countdowns[player.name] = nil
        player:sendMessage("§aCountdown cancelled.")
    else
        player:sendMessage("§cCountdown already expired.")
        countdowns[player.name] = nil
    end
end


-- ==========================================================================
-- Pattern 13: Personal sidebar — per-player scoreboard display
-- ==========================================================================
-- /sidebar              — toggle personal info sidebar on/off
-- /sidebarset <title:text> [<line1:text>] [<line2:text>] [<line3:text>]
--                       — set a custom sidebar with up to 3 lines
--
-- KEY PATTERNS:
--   - player.sidebar = { title = "...", lines = { ... } } sets a per-player
--     sidebar visible only to that player
--   - player.sidebar.title and player.sidebar.lines read/write individual
--     fields without recreating the whole sidebar
--   - player.sidebar = nil clears the sidebar
--   - The sidebar persists across world changes and reconnects
--   - ⚠ Setting player.sidebar.lines = { "a", "b" } replaces ALL lines

local sidebarEnabled = {}

function sidebarHandler(ctx)
    local player = ctx.player

    if sidebarEnabled[player.name] then
        sidebarEnabled[player.name] = nil
        player.sidebar = nil
        player:sendMessage("§aSidebar §cdisabled")
        return
    end

    sidebarEnabled[player.name] = true
    player.sidebar = {
        title = "§6§l" .. player.name,
        lines = {
            "§7━━━━━━━━━━━━━━",
            "§eHP  §f" .. string.format("%.1f", player.health) .. "§7/§f" .. string.format("%.0f", player.maxHealth),
            "§eFood §f" .. player.food,
            "§eLvl  §f" .. player.xpLevel,
            "§ePing §f" .. player.ping .. "ms",
            "§7━━━━━━━━━━━━━━",
        },
    }

    -- Update HP/Food every 2 seconds while enabled
    local id
    id = mc.scheduleRepeating(40, 40, function()
        if not sidebarEnabled[player.name] then
            mc.cancelTask(id)
            return
        end

        local sidebar = player.sidebar
        if sidebar then
            sidebar.title = "§6§l" .. player.name
            sidebar.lines = {
                "§7━━━━━━━━━━━━━━",
                "§eHP  §f" .. string.format("%.1f", player.health) .. "§7/§f" .. string.format("%.0f", player.maxHealth),
                "§eFood §f" .. player.food,
                "§eLvl  §f" .. player.xpLevel,
                "§ePing §f" .. player.ping .. "ms",
                "§7━━━━━━━━━━━━━━",
            }
        end
    end)

    player:sendMessage("§aSidebar §eenabled§a!")
end

function sidebarsetHandler(ctx, title, line1, line2, line3)
    local player = ctx.player
    local lines = { "§7━━━━━━━━━━━━━━" }

    if line1 then table.insert(lines, line1) end
    if line2 then table.insert(lines, line2) end
    if line3 then table.insert(lines, line3) end

    table.insert(lines, "§7━━━━━━━━━━━━━━")

    player.sidebar = { title = title, lines = lines }
    player:sendMessage("§aCustom sidebar set!")
end

-- Show a welcome sidebar when a player joins
mc.on("player_join", function(player)
    player.sidebar = {
        title = "§a§lWelcome!",
        lines = {
            "§7━ §e" .. player.name .. " §7━",
            "",
            "§e/server §7info",
            "§e/help   §7commands",
        },
    }

    -- Clear after 10 seconds
    mc.schedule(200, function()
        if player.sidebar then
            player.sidebar = nil
        end
    end)
end)


-- ==========================================================================
-- Event integration
-- ==========================================================================
-- These handlers react to game events using state set by commands above.

mc.on("player_chat", function(player, message)
    if player.data.muted then
        player:sendMessage("§cYou are muted and cannot chat.")
        return false
    end
end)

mc.on("player_join", function(player)
    local welcome = mc.data.welcomeMessage
    if welcome then
        player:sendMessage("§6" .. welcome)
    end
end)

mc.on("player_leave", function(player)
    player.data.lastSeen = mc.time()
end)

mc.on("player_block_place", function(player, pos, blockId)
    player.data.blockNumber = (player.data.blockNumber or 0) + 1
    if player.data.blockNumber % 10 == 0 then
        player:sendMessage("Вы поставили уже " .. player.data.blockNumber .. " блоков")
    end
end)

mc.on("player_block_break", function(player, pos, blockId)
    player.data.blockNumber = (player.data.blockNumber or 0) - 1

    if player.data.blockNumber % 10 == 0 then
        player:sendMessage("Вы поставили уже " .. player.data.blockNumber .. " блоков")
    end
end)


-- ==========================================================================
-- Command registrations
-- ==========================================================================

register("msg <target:player> <msg:text>",       msgHandler)
register("sethome <name:text>",                  sethomeHandler)
register("home <name:text>",                     homeHandler)
register("homelist",                             homelistHandler)
register("tpa <target:player>",                  tpaHandler)
register("tpaccept <sender:player>",             tpacceptHandler)
register("tpdeny <sender:player>",               tpdenyHandler)
register("mute <target:player>",                 muteHandler,              "pxrp.mod")
register("unmute <target:player>",               unmuteHandler,            "pxrp.mod")
register("setwelcome <msg:text>",                setwelcomeHandler,        "pxrp.admin")
register("setspawn",                             setspawnHandler,          "pxrp.admin")
register("report <msg:text>",                    reportHandler)
register("whois <target:player>",                whoisHandler)

-- New argument types
register("math <a:double> <op:word> <b:double>",    mathHandler)
register("setwarp <name:text> <pos:block_pos>",  setwarpHandler,           "pxrp.admin")

-- Choice + optional args
register("gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]", gamemodeHandler, "pxrp.admin")
register("kick <target:player> [<reason:text>]", kickHandler,              "pxrp.mod")

-- Item & inventory commands
register("kit <name:choice=warrior,archer,miner>",          kitHandler,       "pxrp.admin")
register("hat",                                              hatHandler)
register("rename <name:text>",                               renameHandler)
register("repair",                                           repairHandler,    "pxrp.admin")
register("cleararmor",                                       cleararmorHandler)
register("invsee <target:player>",                           invseeHandler,    "pxrp.mod")

-- World & entity API
register("spawnmob <entity:text>",                                          spawnmobHandler,  "pxrp.admin")
register("tagself <tag:text>",                                               tagselfHandler)
register("worldinfo",                                                        worldinfoHandler)
register("settime <time:choice=day,night,noon,midnight>",                   settimeHandler,   "pxrp.admin")
register("yell <msg:text>",                                                  yellHandler)

-- Scheduler
register("boom <target:player>",       boomHandler,      "pxrp.admin")
register("countdown <seconds:int>",    countdownHandler)
register("cancelcountdown",            cancelcountdownHandler)

-- Personal sidebar
register("sidebar",                                     sidebarHandler)
register("sidebarset <title:text> [<line1:text>] [<line2:text>] [<line3:text>]", sidebarsetHandler)

