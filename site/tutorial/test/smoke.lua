-- Smoke test for a tutorial checkpoint: lua5.2 smoke.lua <mockdir> <file> <chapter>
package.path = arg[1] .. "/?.lua;" .. package.path
local M = require "mock"
local ch = tonumber(arg[3])

local admin = M.player("Admin", { perms = { ["arena.admin"] = true } })
local alice = M.player("Alice")
local bob = M.player("Bob")
alice.pos = vec(100, 64, 100); bob.pos = vec(100, 64, 100)
alice.inv = { [0] = { id = "minecraft:diamond" } }

assert(loadfile(arg[2]))()

if ch >= 2 then
    admin.pos = vec(0, 60, 0); M.run(admin, "arena pos1")
    admin.pos = vec(20, 70, 20); M.run(admin, "arena pos2")
    assert(#M.regions == 1)
    local e = M.fire("block.place", { player = alice, pos = vec(1, 61, 1) }, true)
    assert(e.cancelled, "protected")
end
admin.pos = vec(2, 61, 2); M.run(admin, "arena addspawn")
admin.pos = vec(18, 61, 18); M.run(admin, "arena addspawn")
admin.pos = vec(50, 64, 50); M.run(admin, "arena setlobby")

M.run(alice, "arena join"); M.run(bob, "arena join")
assert(alice.pos.x == 50 and bob.pos.x == 50, "in lobby")

if ch == 1 then
    M.run(bob, "arena leave")
    assert(bob.msgs[#bob.msgs]:find("left"))
    print("OK ch1"); return
end
if ch == 2 then print("OK ch2"); return end

M.seconds(12)
assert(alice.pos.x ~= 50 and bob.pos.x ~= 50, "round started, players at spawns")
if ch >= 7 then assert(alice.data.arenaStash and not alice:has("minecraft:diamond"), "stashed") end
if ch >= 5 then assert(M.bar.title:find("Alive"), M.bar.title); assert(alice.sidebarValue) end

if ch == 3 then
    M.seconds(300)
    assert(alice.msgs[#alice.msgs]:find("draw"), alice.msgs[#alice.msgs])
    M.seconds(6)
    assert(alice.pos.x == 50 and bob.pos.x == 50, "back in lobby after draw")
    print("OK ch3"); return
end

bob.health = 0
local e = M.fire("entity.death", { entity = bob, player = bob, attacker = alice, source = "player_attack", amount = 5 }, true)
assert(e.cancelled and bob.pos.x == 50)
assert(alice.titles[1]:find("Alice"))
M.seconds(6)
assert(alice.pos.x == 50, "winner back in lobby")
if ch >= 6 then
    M.run(alice, "arena stats [<target:player>]")
    assert(alice.msgs[#alice.msgs]:find("1 wins, 1 kills"), alice.msgs[#alice.msgs])
end
if ch >= 7 then assert(alice:has("minecraft:diamond") and not alice.data.arenaStash, "restored") end
if ch >= 8 then assert(#M.emitted == 1) else assert(#M.emitted == 0) end
print("OK ch" .. ch)
