-- ==========================================================================
-- demo.lua — Practical Lua patterns for PxIgnis
-- ==========================================================================
-- Usage:  Place this file in the config/ignis/ directory.
--         On first run it is copied there automatically.
--
-- This file demonstrates useful scripting patterns, not just API methods.
-- Each section shows a different concept you can use in your own scripts.
-- All messages use § color codes for visual clarity (works in Minecraft chat).
--
-- Contents:
--   1. Private messaging      — cross-player direct messages
--   2. Home system            — persisted named locations per player
--   3. Teleport requests      — time-limited cross-player state
--   4. Mute / moderation      — command + event integration
--   5. Global config          — data-driven runtime settings
--   6. Report cooldown        — per-player throttle + notifications
--   7. New arg types          — int, double, bool, block_pos
--   8. Choice & optional      — choice=... syntax and [name:type] optional args
--   9. Player info            — reading aggregate entity data, hasPermission
--  10. Item & inventory       — kits, hats, rename, repair, invsee
--  11. World & entity         — spawn, tags, time, weather, particle, broadcastInRange
--  12. Scheduler              — mc.schedule, mc.scheduleRepeating, mc.cancelTask
--  13. Personal sidebar       — per-player scoreboard display
--  14. Vector arithmetic      — vec(), +, -, *, /, ==, tostring
--  15. World block manip.     — setBlock, getBlock, fill
--  16. Particles, sounds, …   — particle, playSound, getEntities, raycast
--  17. Advanced mc API        — world(), players(), getEntity(), dump(), getMetatable()
--  18. Advanced player meth.  — action bar, title, effects, damage, heal, raycast
--  19. Entity NBT & ops       — readNbt, writeNbt, damage, effects, setOnFire
--  20. Structure API          — loadStructure, loadStructureFile, place
--  21. Item details & ser.    — properties, serialise, deserialise
--  22. Module system          — require "format", require "simple"
--  23. Chest GUI              — chestgui.create(), grid-based interactive GUI
--  24. Shared inventory       — serialise/deserialise, persist across reloads
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
-- Deep writes now work directly:
--   player.data.homes[name] = {...}
-- persists on save. The read-modify-write pattern below still works
-- and can be useful for read-before-write logic, but is no longer
-- required for correctness.

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

-- /checkperm <permission> — check if the player has a specific permission
-- Uses the Fabric Permissions API (LuckPerms, etc.) or OP levels.
-- Returns true/false based on the permission lookup.
function checkpermHandler(ctx, perm)
    local result = ctx.player:hasPermission(perm)
    ctx.player:sendMessage("§aHas §f'" .. perm .. "'§7: §f" .. tostring(result))
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
--   - world:particle(id, pos, {count?, spread?, speed?, ...}) spawns particles; particle-type fields go directly in opts

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
--   - player.sidebar = { title = "...", lines = {...} } creates a sidebar (auto-shown on creation)
--   - sb.title / sb.lines are read/write properties
--   - sb:setLine(n, text) sets a specific line at index n
--   - sb:show() displays, sb:hide() hides (keeps data), sb:destroy() removes
--   - sb.visible, sb.lineCount are readable properties

local sidebarEnabled = {}
local activeSidebars = {}

function sidebarHandler(ctx)
    local player = ctx.player

    if sidebarEnabled[player.name] then
        sidebarEnabled[player.name] = nil
        local sb = activeSidebars[player.name]
        if sb then
            sb:destroy()
            activeSidebars[player.name] = nil
        end
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
        }
    }
    local sb = player.sidebar
    activeSidebars[player.name] = sb

    -- Update HP/Food every 2 seconds while enabled
    local id
    id = mc.scheduleRepeating(40, 40, function()
        if not sidebarEnabled[player.name] then
            mc.cancelTask(id)
            return
        end

        local sb = activeSidebars[player.name]
        if sb then
            sb.title = "§6§l" .. player.name
            sb.lines = {
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
mc.on("player_join_init", function(player)
    player.sidebar = {
        title = "§a§lWelcome!",
        lines = {
            "§7━ §e" .. player.name .. " §7━",
            "",
            "§e/server §7info",
            "§e/help   §7commands",
        }
    }
    local sb = player.sidebar

    -- Clear after 10 seconds
    mc.schedule(200, function()
        pcall(function() sb:destroy() end)
    end)
end)


-- ==========================================================================
-- Pattern 14: Vector arithmetic — vec(), +, -, *, /, ==, tostring
-- ==========================================================================
-- /vecadd <x1:int> <y1:int> <z1:int> <x2:int> <y2:int> <z2:int>
-- /veceq <x1:int> <y1:int> <z1:int> <x2:int> <y2:int> <z2:int>

function vecaddHandler(ctx, x1, y1, z1, x2, y2, z2)
    local a = vec(x1, y1, z1)
    local b = vec(x2, y2, z2)
    ctx.player:sendMessage("§a" .. tostring(a) .. " + " .. tostring(b) .. " = " .. tostring(a + b))
end

function veceqHandler(ctx, x1, y1, z1, x2, y2, z2)
    local a = vec(x1, y1, z1)
    local b = vec(x2, y2, z2)
    ctx.player:sendMessage("§a" .. tostring(a) .. " == " .. tostring(b) .. " §7→ §f" .. tostring(a == b))
end


-- ==========================================================================
-- Pattern 15: World block manipulation — setBlock, getBlock, fill
-- ==========================================================================
-- /setblock <pos:block_pos> <block:text>
-- /getblock <pos:block_pos>
-- /fillblock <pos1:block_pos> <pos2:block_pos> <block:text>

function setblockHandler(ctx, pos, blockId)
    ctx.player.world:setBlock(pos, blockId)
    ctx.player:sendMessage("§aSet §f" .. blockId .. " §aat " .. pos.x .. ", " .. pos.y .. ", " .. pos.z)
end

function getblockHandler(ctx, pos)
    local id = ctx.player.world:getBlock(pos)
    ctx.player:sendMessage("§aBlock at " .. pos.x .. ", " .. pos.y .. ", " .. pos.z .. " §7→ §f" .. id)
end

function fillblockHandler(ctx, pos1, pos2, blockId)
    ctx.player.world:fill(pos1, pos2, blockId)
    ctx.player:sendMessage("§aFilled area with §f" .. blockId)
end


-- ==========================================================================
-- Pattern 16: Particles, sounds, entity queries, world raycasting
-- ==========================================================================
-- /particle <id:text> [<count:int>]
-- /playsound <id:text> [<volume:double>]
-- /nearby <radius:double> [<type:text>]
-- /worldraycast <range:double>

function particleHandler(ctx, id, count)
    local p = ctx.player
    p.world:particle(id, p.pos, {
        count = count or 10,
        spread = vec(0.5, 0.5, 0.5),
        speed = 0.1,
    })
    p:sendMessage("§aSpawned §f" .. (count or 10) .. " §aof §f" .. id)
end

function playsoundHandler(ctx, id, volume)
    local p = ctx.player
    local pos = p.pos
    p.world:playSound(id, pos, volume or 1.0, 1.0)
    p:sendMessage("§aPlaying §f" .. id)
end

function nearbyHandler(ctx, radius, entityType)
    local p = ctx.player
    local entities = p.world:getEntities(p.pos, radius, entityType)
    local names = {}
    for _, e in ipairs(entities) do
        table.insert(names, e.name .. " §7(§f" .. e.type .. "§7)")
    end
    if #names == 0 then p:sendMessage("§cNo entities within §f" .. radius .. " §cblocks"); return end
    p:sendMessage("§aNearby (§f" .. #names .. "§a): §f" .. table.concat(names, "§7, §f"))
end

function worldraycastHandler(ctx, range)
    local p = ctx.player
    local r = p.world:raycast(p.pos, p.dir, range, false, true)
    if r then
        local msg = "§aHit §f" .. string.format("%.1f, %.1f, %.1f", r.hit.x, r.hit.y, r.hit.z)
        if r.entity then msg = msg .. " §7→ §f" .. r.entity.name end
        p:sendMessage(msg)
    else
        p:sendMessage("§cNothing in range")
    end
end


-- ==========================================================================
-- Pattern 17: Advanced mc API — world(), players(), getEntity(), dump()
-- ==========================================================================
-- /worldlist                  — iterate loaded worlds
-- /playerlist                 — list online players
-- /onlinecount                — show mc.onlineCount
-- /entitylookup <uuid:text>   — lookup any entity by UUID
-- /dumpentity                 — dump yourself with mc.dump()
-- /metatables                 — list available metatables

function worldlistHandler(ctx)
    local worlds = { "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end" }
    local lines = {}
    for _, name in ipairs(worlds) do
        local w = mc.world(name)
        if w then table.insert(lines, "  §7- §f" .. name .. " §7(time: §f" .. math.floor(w.time) .. "§7)") end
    end
    ctx.player:sendMessage("§6Loaded worlds:\n" .. table.concat(lines, "\n"))
end

function playerlistHandler(ctx)
    local players = mc.players
    local lines = {}
    for _, p in ipairs(players) do
        table.insert(lines, "  §7- §f" .. p.name .. " §7(§f" .. p.ping .. "ms§7, §f" .. p.world.name .. "§7)")
    end
    ctx.player:sendMessage("§6Online (§f" .. #players .. "§6):\n" .. table.concat(lines, "\n"))
end

function onlinecountHandler(ctx)
    ctx.player:sendMessage("§aOnline: §f" .. mc.onlineCount)
end

function entitylookupHandler(ctx, uuid)
    local e = mc.getEntity(uuid)
    if not e then ctx.player:sendMessage("§cEntity §f'" .. uuid .. "'§c not found"); return end
    local pos = e.pos
    ctx.player:sendMessage(
        "§6Entity: §f" .. e.name .. " §7(§f" .. e.type .. "§7)\n"
        .. "§7Health: §f" .. string.format("%.1f", e.health) .. "\n"
        .. "§7World:  §f" .. e.world.name .. "\n"
        .. "§7Pos:    §f" .. string.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)
    )
end

function dumpentityHandler(ctx)
    ctx.player:sendMessage(mc.dump(ctx.player, 2))
end

function metatablesHandler(ctx)
    local names = { "vec", "player", "entity", "world", "item" }
    local lines = {}
    for _, name in ipairs(names) do
        table.insert(lines, "  §7- §f" .. name .. " §7(" .. tostring(mc.getMetatable(name) ~= nil) .. ")")
    end
    ctx.player:sendMessage("§6Metatables:\n" .. table.concat(lines, "\n"))
end


-- ==========================================================================
-- Pattern 18: Advanced player methods
-- ==========================================================================
-- /actionbar <msg:text>
-- /sendtitle <title:text> [<subtitle:text>]
-- /suicide
-- /healme
-- /playersound <id:text> [<volume:double>]
-- /setitemslot <slot:int> <id:text> [<count:int>]
-- /playereffect <id:text> <duration:int> [<amplifier:int>]
-- /removeeffect <id:text>
-- /haseffect <id:text>
-- /playerraycast <range:double>

function actionbarHandler(ctx, msg)
    ctx.player:sendActionBar(msg)
end

function sendtitleHandler(ctx, title, subtitle)
    ctx.player:sendTitle(title, subtitle)
end

function suicideHandler(ctx)
    ctx.player:damage(100)
    ctx.player:sendMessage("§cOuch!")
end

function healmeHandler(ctx)
    local p = ctx.player
    p:heal(p.maxHealth - p.health)
    p:sendMessage("§aHealed!")
end

function playersoundHandler(ctx, id, volume)
    ctx.player:playSound(id, volume or 1.0, 1.0)
end

function setitemslotHandler(ctx, slot, id, count)
    ctx.player:setItem(slot, mc.createItem(id, { count = count or 1 }))
    ctx.player:sendMessage("§aSet slot §f" .. slot .. " §7→ §f" .. id)
end

function playereffectHandler(ctx, id, duration, amplifier)
    ctx.player:addEffect(id, duration, amplifier or 0, true, true)
    ctx.player:sendMessage("§aApplied §f" .. id)
end

function removeeffectHandler(ctx, id)
    ctx.player:removeEffect(id)
    ctx.player:sendMessage("§aRemoved §f" .. id)
end

function haseffectHandler(ctx, id)
    ctx.player:sendMessage("§aHas §f" .. id .. "§7: §f" .. tostring(ctx.player:hasEffect(id)))
end

function playerraycastHandler(ctx, range)
    local r = ctx.player:raycast(range, false, true)
    if r then
        local msg = "§aHit §f" .. string.format("%.1f, %.1f, %.1f", r.hit.x, r.hit.y, r.hit.z)
        if r.entity then msg = msg .. " §7→ §f" .. r.entity.name end
        ctx.player:sendMessage(msg)
    else
        ctx.player:sendMessage("§cNothing in range")
    end
end


-- ==========================================================================
-- Pattern 19: Entity NBT & advanced operations
-- ==========================================================================
-- /entitynbt <uuid:text>
-- /entitydamage <uuid:text> <amount:double>
-- /entityeffect <uuid:text> <id:text> <duration:int>
-- /setfire <uuid:text> <ticks:int>

function entitynbtHandler(ctx, uuid)
    local e = mc.getEntity(uuid)
    if not e then ctx.player:sendMessage("§cEntity not found"); return end
    ctx.player:sendMessage("§6NBT for §f" .. e.name .. "§6:\n" .. mc.dump(e:readNbt(), 2))
end

function entitydamageHandler(ctx, uuid, amount)
    local e = mc.getEntity(uuid)
    if not e then ctx.player:sendMessage("§cEntity not found"); return end
    e:damage(amount)
    ctx.player:sendMessage("§aDealt §f" .. amount .. " §adamage to §f" .. e.name)
end

function entityeffectHandler(ctx, uuid, effectId, duration)
    local e = mc.getEntity(uuid)
    if not e then ctx.player:sendMessage("§cEntity not found"); return end
    e:addEffect(effectId, duration, 0, true, true)
    ctx.player:sendMessage("§aApplied §f" .. effectId .. " §ato §f" .. e.name)
end

function setfireHandler(ctx, uuid, ticks)
    local e = mc.getEntity(uuid)
    if not e then ctx.player:sendMessage("§cEntity not found"); return end
    e:setOnFireFor(ticks)
    ctx.player:sendMessage("§aSet §f" .. e.name .. " §aon fire for §f" .. ticks .. " §aticks")
end


-- ==========================================================================
-- Pattern 20: Structure API — load, inspect, place
-- ==========================================================================
-- /structinfo <name:text>
-- /structplace <name:text>

function structinfoHandler(ctx, name)
    local s = mc.loadStructure(name)
    if not s then ctx.player:sendMessage("§cStructure §f'" .. name .. "'§c not found"); return end
    ctx.player:sendMessage("§6Structure: §f" .. name .. "\n  §7Size: §f" .. s.size.x .. " × " .. s.size.y .. " × " .. s.size.z)
end

function structplaceHandler(ctx, name)
    local s = mc.loadStructure(name)
    if not s then ctx.player:sendMessage("§cStructure §f'" .. name .. "'§c not found"); return end
    local p = ctx.player
    s:place(p.world, { x = p.pos.x, y = p.pos.y, z = p.pos.z }, { rotation = "none", mirror = "none" })
    p:sendMessage("§aPlaced §f" .. name)
end


-- ==========================================================================
-- Pattern 21: ItemStack details & serialisation
-- ==========================================================================
-- /iteminfo
-- /itemserialise
-- /itemdeserialise <json:text>

function iteminfoHandler(ctx)
    local held = ctx.player.mainhand
    if not held then ctx.player:sendMessage("§cHold an item"); return end
    local lines = { "§6--- Held item ---", "§7ID:      §f" .. (held.id or "?") }
    if held.name               then table.insert(lines, "§7Name:    §f" .. held.name) end
    if held.unbreakable        then table.insert(lines, "§7Unbreakable: §f" .. tostring(held.unbreakable)) end
    if held.custom_model_data  then table.insert(lines, "§7CMD:     §f" .. held.custom_model_data) end
    ctx.player:sendMessage(table.concat(lines, "\n"))
end

function itemserialiseHandler(ctx)
    local held = ctx.player.mainhand
    if not held then ctx.player:sendMessage("§cHold an item"); return end
    ctx.player:sendMessage("§6JSON: §f" .. mc.serialise("item", held))
end

function itemdeserialiseHandler(ctx, json)
    local item = mc.deserialise("item", json)
    if not item then ctx.player:sendMessage("§cFailed to deserialise"); return end
    ctx.player:give(item)
    ctx.player:sendMessage("§aItem restored from JSON!")
end


-- ==========================================================================
-- Pattern 22: Module system — require "format", require "simple"
-- ==========================================================================
-- The VFS-based require resolves dotted and namespaced module names:
--   require "core:format"   → built-in module
--   require "core:simple"   → built-in module
--   require "foo.bar"       → config/ignis/foo/bar.lua
--   require "mymod:utils"   → config/ignis/mymod/utils.lua
--
-- require("core:format") returns a callable table:
--   format(template)   — returns a function(args) that substitutes {expr}
--   format.broadcast(template) — returns a function(args) that renders and broadcasts.
--
-- simple.register(syntax, template, range?, overlay?):
--   one-shot registration — no handler needed. The generated handler
--   builds an args table with {p = ctx.player, ...} and broadcasts.
--
-- Uncomment to enable:
--   local simple = require("core:simple")
--   simple.register("shout <msg:text>", "§6[SHOUT] §e{p.name}§7: §f{msg}", 100)
-- ==========================================================================

-- ==========================================================================
-- Pattern 23: Chest GUI — interactive grid-based inventory GUI
-- ==========================================================================
-- /shop             — opens a buy menu with items in a chest GUI
-- /shopadmin        — admin: open a shared view-only inventory
-- /addcoins <target:player> <coins:int>  — give coins for testing
--
-- KEY PATTERNS:
--   - chestgui.create(rows, title) returns a fresh GUI builder
--   - gui:set(row, col, item, callback) places a clickable item
--   - gui:decorate(row, col, item) places a non-interactive item
--   - gui:open(player) opens the GUI for that player → returns Container
--   - Click callback receives (player, slot, clickType, slotItem, cursorItem)
--   - Returning false cancels the click (prevents item removal/swap)
--   - Rows and cols are 1-based (1..6 rows, 1..9 cols)

local chestgui = require "core:chestgui"

-- Pre-build the shop GUI once — reused for all players
local shopGui = chestgui.create(3, "§6§lShop")
shopGui:set(2, 3, mc.createItem("minecraft:diamond", { name = "§bDiamond §7(§f10 coins§7)" }),
    function(player, slot, clickType, slotItem, cursorItem)
        local coins = player.data.coins or 0
        if coins < 10 then
            player:sendMessage("§cNot enough coins! §7Need 10, have " .. coins)
            return false
        end
        player.data.coins = coins - 10
        player:give(mc.createItem("minecraft:diamond"))
        player:sendMessage("§aBought a diamond! §7Coins left: " .. (coins - 10))
        return false
    end)
shopGui:set(2, 5, mc.createItem("minecraft:emerald", { name = "§aEmerald §7(§f5 coins§7)" }),
    function(player, slot, clickType, slotItem, cursorItem)
        local coins = player.data.coins or 0
        if coins < 5 then
            player:sendMessage("§cNot enough coins! §7Need 5, have " .. coins)
            return false
        end
        player.data.coins = coins - 5
        player:give(mc.createItem("minecraft:emerald"))
        player:sendMessage("§aBought an emerald! §7Coins left: " .. (coins - 5))
        return false
    end)
shopGui:set(2, 7, mc.createItem("minecraft:iron_ingot", { name = "§7Iron §7(§f2 coins§7)" }),
    function(player, slot, clickType, slotItem, cursorItem)
        local coins = player.data.coins or 0
        if coins < 2 then
            player:sendMessage("§cNot enough coins! §7Need 2, have " .. coins)
            return false
        end
        player.data.coins = coins - 2
        player:give(mc.createItem("minecraft:iron_ingot"))
        player:sendMessage("§aBought an iron ingot! §7Coins left: " .. (coins - 2))
        return false
    end)

-- Decorative glass border — no callback = non-interactive
local glass = mc.createItem("minecraft:black_stained_glass_pane", { name = " " })
for col = 1, 9 do
    shopGui:decorate(1, col, glass)
    shopGui:decorate(3, col, glass)
end
shopGui:decorate(2, 1, glass)
shopGui:decorate(2, 9, glass)

-- Info signs
shopGui:decorate(2, 2, mc.createItem("minecraft:gold_nugget",
    { name = "§eYour coins", lore = { "§7Stored in player.data.coins" } }))
shopGui:decorate(2, 8, mc.createItem("minecraft:book",
    { name = "§eHelp", lore = { "§7/shop — open menu", "§7/addcoins — give coins" } }))

function shopHandler(ctx)
    shopGui:open(ctx.player)
end

function shopadminHandler(ctx)
    local inv = mc.createInventory(27)
    inv:setItem(14, mc.createItem("minecraft:barrier", { name = "§cPlace items above" }))
    ctx.player:sendMessage("§aOpening a shared inventory")
    inv:open(ctx.player, "Admin View")
end

function addcoinsHandler(ctx, target, amount)
    local coins = target.data.coins or 0
    target.data.coins = coins + amount
    ctx.player:sendMessage("§aGave §f" .. target.name .. " §a" .. amount .. " coins. §7Now: " .. (coins + amount))
    target:sendMessage("§aYou received §f" .. amount .. " §acoins!")
end

-- ==========================================================================
-- Pattern 24: Shared inventory — persist across reloads
-- ==========================================================================
-- /sharedchest        — opens a shared chest that persists across reloads
-- /sharedchestrestore — admin: restore the shared chest after serialising
--
-- KEY PATTERNS:
--   - inv:serialise() returns a JSON string (like item:serialise())
--   - mc.serialise("inventory", inv) is the equivalent global call
--   - mc.deserialise("inventory", json) recreates the inventory from JSON
--   - Store the JSON string in mc.data to survive /ignis reload
--   - Serialise on server_stop to persist across actual shutdown
--   - Also serialise in onClick to save after each player interaction

local sharedChest

-- Restore from saved data (survives /ignis reload)
local savedJson = mc.data.sharedChest
if savedJson then
    sharedChest = mc.deserialise("inventory", savedJson)
else
    sharedChest = mc.createInventory(27)
end

-- Save the shared chest state whenever it changes
local function saveSharedChest()
    mc.data.sharedChest = sharedChest:serialise()
end

-- Persist across server restarts
mc.on("server_stop", saveSharedChest)

function sharedchestHandler(ctx)
    local container = sharedChest:open(ctx.player, "§6Shared Chest")
    if container then
        -- Save after every click so reloads don't lose recent changes
        container:onClick(function(player, slot, clickType, slotItem, cursorItem)
            saveSharedChest()
            return true  -- allow items to move freely
        end)
        ctx.player:sendMessage("§aOpened shared chest. Items persist across reloads!")
    end
end

-- Admin: dump the serialised state to chat (for debugging)
function sharedchestrestoreHandler(ctx)
    local json = mc.data.sharedChest
    if json then
        sharedChest = mc.deserialise("inventory", json)
        ctx.player:sendMessage("§aShared chest restored from saved data. §7Size: " .. sharedChest.size)
    else
        ctx.player:sendMessage("§cNo saved shared chest data found.")
    end
end

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

mc.on("player_join_init", function(player)
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

mc.on("player_death", function(player)
    player:sendMessage("§cYou died at " .. string.format("%.1f, %.1f, %.1f", player.pos.x, player.pos.y, player.pos.z))
end)

mc.on("player_use_item", function(player, hand)
end)

mc.on("player_attack_entity", function(player, target)
    player:sendMessage("§eYou attacked §f" .. target.name)
end)

mc.on("player_interact_entity", function(player, target)
    player:sendMessage("§eYou interacted with §f" .. target.name)
end)

mc.on("player_hurt", function(player, source, amount)
end)

mc.on("entity_hurt", function(entity, source, amount)
end)

mc.on("player_damage", function(player, source, amount)
end)

mc.on("entity_damage", function(entity, source, amount)
end)

mc.on("player_kill", function(player, target)
    player:sendMessage("§cYou killed §f" .. target.name)
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
register("mute <target:player>",                 muteHandler,              "px.ignis.mod")
register("unmute <target:player>",               unmuteHandler,            "px.ignis.mod")
register("setwelcome <msg:text>",                setwelcomeHandler,        "px.ignis.admin")
register("setspawn",                             setspawnHandler,          "px.ignis.admin")
register("report <msg:text>",                    reportHandler)
register("whois <target:player>",                whoisHandler)
register("checkperm <perm:text>",                checkpermHandler)

-- New argument types
register("math <a:double> <op:word> <b:double>",    mathHandler)
register("setwarp <name:text> <pos:block_pos>",  setwarpHandler,           "px.ignis.admin")

-- Choice + optional args
register("gamemode <mode:choice=creative,survival,adventure,spectator> [<target:player>]", gamemodeHandler, "px.ignis.admin")
register("kick <target:player> [<reason:text>]", kickHandler,              "px.ignis.mod")

-- Item & inventory commands
register("kit <name:choice=warrior,archer,miner>",          kitHandler,       "px.ignis.admin")
register("hat",                                              hatHandler)
register("rename <name:text>",                               renameHandler)
register("repair",                                           repairHandler,    "px.ignis.admin")
register("cleararmor",                                       cleararmorHandler)
register("invsee <target:player>",                           invseeHandler,    "px.ignis.mod")

-- World & entity API
register("spawnmob <entity:text>",                                          spawnmobHandler,  "px.ignis.admin")
register("tagself <tag:text>",                                               tagselfHandler)
register("worldinfo",                                                        worldinfoHandler)
register("settime <time:choice=day,night,noon,midnight>",                   settimeHandler,   "px.ignis.admin")
register("yell <msg:text>",                                                  yellHandler)

-- Scheduler
register("boom <target:player>",       boomHandler,      "px.ignis.admin")
register("countdown <seconds:int>",    countdownHandler)
register("cancelcountdown",            cancelcountdownHandler)

-- Personal sidebar
register("sidebar",                                     sidebarHandler)
register("sidebarset <title:text> [<line1:text>] [<line2:text>] [<line3:text>]", sidebarsetHandler)

-- Vector arithmetic
register("vecadd <x1:int> <y1:int> <z1:int> <x2:int> <y2:int> <z2:int>", vecaddHandler)
register("veceq <x1:int> <y1:int> <z1:int> <x2:int> <y2:int> <z2:int>", veceqHandler)

-- World block manipulation
register("setblock <pos:block_pos> <block:text>",       setblockHandler,    "px.ignis.admin")
register("getblock <pos:block_pos>",                     getblockHandler)
register("fillblock <pos1:block_pos> <pos2:block_pos> <block:text>", fillblockHandler, "px.ignis.admin")

-- Particles, sounds, queries
register("particle <id:text> [<count:int>]",            particleHandler,    "px.ignis.admin")
register("playsound <id:text> [<volume:double>]",       playsoundHandler)
register("nearby <radius:double> [<type:text>]",        nearbyHandler)
register("worldraycast <range:double>",                 worldraycastHandler)

-- Advanced mc API
register("worldlist",                                   worldlistHandler)
register("playerlist",                                  playerlistHandler)
register("onlinecount",                                 onlinecountHandler)
register("entitylookup <uuid:text>",                    entitylookupHandler)
register("dumpentity",                                  dumpentityHandler)
register("metatables",                                  metatablesHandler)

-- Advanced player methods
register("actionbar <msg:text>",                        actionbarHandler)
register("sendtitle <title:text> [<subtitle:text>]",    sendtitleHandler)
register("suicide",                                     suicideHandler)
register("healme",                                      healmeHandler)
register("playersound <id:text> [<volume:double>]",     playersoundHandler)
register("setitemslot <slot:int> <id:text> [<count:int>]", setitemslotHandler, "px.ignis.admin")
register("playereffect <id:text> <duration:int> [<amplifier:int>]", playereffectHandler)
register("removeeffect <id:text>",                      removeeffectHandler)
register("haseffect <id:text>",                         haseffectHandler)
register("playerraycast <range:double>",                playerraycastHandler)

-- Entity NBT & ops
register("entitynbt <uuid:text>",                       entitynbtHandler,   "px.ignis.admin")
register("entitydamage <uuid:text> <amount:double>",    entitydamageHandler, "px.ignis.admin")
register("entityeffect <uuid:text> <id:text> <duration:int>", entityeffectHandler, "px.ignis.admin")
register("setfire <uuid:text> <ticks:int>",             setfireHandler,     "px.ignis.admin")

-- Structure
register("structinfo <name:text>",                      structinfoHandler)
register("structplace <name:text>",                     structplaceHandler, "px.ignis.admin")

-- Chest GUI
register("shop",                                          shopHandler)
register("shopadmin",                                     shopadminHandler,   "px.ignis.admin")
register("addcoins <target:player> <coins:int>",          addcoinsHandler,    "px.ignis.admin")

-- Shared inventory
register("sharedchest",                                       sharedchestHandler)
register("sharedchestrestore",                                sharedchestrestoreHandler, "px.ignis.admin")

-- Item details & serialisation
register("iteminfo",                                    iteminfoHandler)
register("itemserialise",                               itemserialiseHandler)
register("itemdeserialise <json:text>",                 itemdeserialiseHandler)

