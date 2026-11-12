-- Plays the finished arena through: setup, kits, rounds, knockouts, leaving, reloads, a restart.
-- lua5.2 scenario.lua <this dir> <arena.lua> [arena_rewards.lua]
package.path = arg[1] .. "/?.lua;" .. package.path
local M = require "mock"
local script = arg[2]

local admin = M.player("Admin", { perms = { ["arena.admin"] = true } })
local alice = M.player("Alice")
local bob = M.player("Bob")
for _, p in ipairs({ alice, bob }) do p.pos = vec(100, 64, 100) end

assert(loadfile(script))()
if arg[3] then assert(loadfile(arg[3]))() end

-- setup
admin.pos = vec(0.5, 60, 0.5); M.run(admin, "arena pos1")
admin.pos = vec(20.2, 70, 20.9); M.run(admin, "arena pos2")
assert(#M.regions == 1, "region after both corners")
local r = M.regions[1]
assert(r:contains(vec(20.5, 70.5, 20.5)) and not r:contains(vec(21, 70, 20)), "max block included")
M.run(alice, "arena join")
assert(alice.msgs[#alice.msgs]:find("not set up"), "join before setup refused")
admin.pos = vec(2, 61, 2); M.run(admin, "arena addspawn")
admin.pos = vec(18, 61, 18); M.run(admin, "arena addspawn")
admin.pos = vec(50, 64, 50); M.run(admin, "arena setlobby")
admin.pos = vec(52, 64, 50); M.run(admin, "arena setleaderboard")
assert(M.hologram and M.hologram.lines[2]:find("no winners"), "empty leaderboard")
assert(#mc.data.arena.spawns == 2)

-- kits
M.run(alice, "arena kit")
M.openGui:click(alice, 5) -- second kit (col 5)
assert(alice.data.arenaKit == "archer", tostring(alice.data.arenaKit))

-- inventories to stash
alice.inv = { [0] = { id = "minecraft:diamond" }, [38] = { id = "minecraft:elytra" } }
bob.inv = { [5] = { id = "minecraft:stone" } }

M.run(alice, "arena join")
assert(alice.pos.x == 50, "teleported to lobby")
M.seconds(2)
assert(M.bar.title:find("Waiting"), M.bar.title)
M.run(bob, "arena join")
M.seconds(1)
assert(M.bar.title:find("Starting in"), M.bar.title)
M.seconds(10)
assert(M.bar.title:find("Alive: 2"), M.bar.title)
assert(alice.data.arenaStash["0"] == "json:minecraft:diamond" and alice.data.arenaStash["38"] == "json:minecraft:elytra")
assert(alice:has("minecraft:bow") and not alice:has("minecraft:diamond"), "archer kit, own items stashed")
assert(bob:has("minecraft:iron_sword"), "warrior kit default")
assert(alice.chestItem.id == "minecraft:leather_chestplate")
assert(r:contains(alice.pos) and r:contains(bob.pos), "players in arena")
assert(alice.sidebarValue.lines[1]:find("fighting"))

-- block protection
local e = M.fire("block.break", { player = alice, pos = vec(5, 61, 5) }, true)
assert(e.cancelled, "arena protected")
e = M.fire("block.break", { player = admin, pos = vec(5, 61, 5) }, true)
assert(not e.cancelled, "admin can build")
e = M.fire("block.break", { player = alice, pos = vec(500, 61, 5) }, true)
assert(not e.cancelled, "outside not protected")

-- alice kills bob
bob.health = 0
e = M.fire("entity.death", { entity = bob, player = bob, attacker = alice, source = "player_attack", amount = 5 }, true)
assert(e.cancelled and bob.health == 20, "death cancelled")
assert(bob.pos.x == 50, "bob back in lobby")
assert(bob.inv[5].id == "minecraft:stone" and bob.data.arenaStash == nil, "bob items back")
assert(bob.data.arenaStats.deaths == 1 and alice.data.arenaStats.kills == 1)
assert(#M.emitted == 0, "win announced after the celebration")
assert(alice.titles[1]:find("Alice"))
assert(M.hologram.lines[2]:find("Alice") and M.hologram.lines[2]:find("1"), M.hologram.lines[2])
-- winner leaves region during celebration: no elimination
alice.pos = vec(100, 64, 100); M.tick(1)
alice.pos = vec(5, 61, 5)
M.seconds(6)
assert(alice.pos.x == 50 and alice.inv[38].id == "minecraft:elytra" and not alice:has("minecraft:bow"), "alice restored")
assert(#M.emitted == 1 and M.emitted[1][1] == "arena:win" and M.emitted[1][2] == alice and M.emitted[1][3] == 1)
if arg[3] then
    local d = alice.inv[1]
    assert(d and d.id == "minecraft:diamond" and d.count == 2, "reward kept after restore")
end
assert(alice.data.arenaStats.deaths == 0 and alice.data.arenaStats.wins == 1)

-- next round starts automatically, bob walks out of the arena
M.seconds(11)
assert(M.bar.title:find("Alive: 2"), M.bar.title)
bob.pos = vec(100, 64, 100); M.tick(1)
assert(bob.msgs[#bob.msgs - 1]:find("left the arena") or bob.msgs[#bob.msgs]:find("eliminated"), bob.msgs[#bob.msgs])
assert(bob.pos.x == 50 and alice.data.arenaStats.wins == 2 or alice.data.arenaStats.wins == 2)
M.seconds(6)

-- third round: bob disconnects mid-round
M.seconds(11)
assert(M.bar.title:find("Alive: 2"), M.bar.title)
M.fire("player.leave", { player = bob })
bob.online = false
assert(alice.data.arenaStats.wins == 3)
M.seconds(6)

-- a stranger walks into a running round
local carol = M.player("Carol"); carol.pos = vec(100, 64, 100)
M.run(carol, "arena join")
M.seconds(11)
assert(M.bar.title:find("Alive: 2"), M.bar.title)
local dave = M.player("Dave"); dave.pos = vec(100, 64, 100); M.tick(1)
dave.pos = vec(3, 61, 3); M.tick(1)
assert(dave.pos.x == 50, "stranger teleported out")

-- crash: carol has a stash; simulate a lost state
local stash = carol.data.arenaStash
assert(stash, "carol stashed")
M.fire("uninit", {})
assert(carol.data.arenaStash == nil and carol.pos.x == 50, "uninit restores")

-- persisted data survives a JSON round trip (restart)
mc.data = M.roundtrip(mc.data)
M.reset()
assert(loadfile(script))()
assert(#M.regions == 1 and M.regions[1]:contains(vec(20.5, 70.5, 20.5)), "region rebuilt after restart")
local eve = M.player("Eve"); eve.pos = vec(100, 64, 100)
eve.data = M.roundtrip(M.persist({ arenaStash = { ["3"] = "json:minecraft:apple" } }))
M.fire("player.join", { player = eve })
assert(eve.inv[3].id == "minecraft:apple" and eve.pos.x == 50, "crash recovery")
M.run(eve, "arena stats [<target:player>]")
assert(eve.msgs[#eve.msgs]:find("0 wins"), eve.msgs[#eve.msgs])
assert(M.hologram.lines[2]:find("Alice"), "leaderboard after restart")
print("OK: " .. #M.log .. " log lines")
