-- ignis-mapcanvas smoke checks, run alongside smoke/ignis-smoke.lua by plugins/mapcanvas/smoke/run.sh.
-- No client is connected: viewer-side packets are not exercised, only the Lua surface and the tick loop.
local function step(name, fn)
    local ok, err = pcall(fn)
    print("[SMOKE] mapcanvas " .. name .. ": " .. (ok and "OK" or ("FAIL " .. tostring(err))))
end

local mapcanvas = require("ext:mapcanvas")
local cv, cv2

step("canvas", function()
    cv = mapcanvas.canvas()
    cv2 = mapcanvas.canvas()
    assert(type(cv.id) == "number" and cv.id >= 1000000000, "id " .. tostring(cv.id))
    assert(cv2.id == cv.id + 1)
    assert(#cv.viewers == 0)
end)

step("palette", function()
    assert(mapcanvas.color("grass", 1) == 5)
    assert(mapcanvas.color("color_red", 2) == 114)
    assert(mapcanvas.color("red") == 113)
    assert(mapcanvas.rgb(255, 255, 255) == mapcanvas.color("snow", 2))
    assert(not pcall(mapcanvas.color, "nope", 1))
    assert(not pcall(mapcanvas.color, "stone", 9))
end)

step("draw", function()
    local red = mapcanvas.color("red", 2)
    cv:fill(-10, -10, 50, 50, red)
    cv:pixel(127, 127, red)
    cv:pixel(500, 500, red)
    cv:blit(120, 120, 4, 4, string.rep("\5", 16))
    cv:clear()
    cv:fill(0, 0, 128, 128, red)
    assert(not pcall(cv.blit, cv, 0, 0, 4, 4, "short"))
    assert(not pcall(cv.fill, cv, 0, 0, 1, 1, 300))
end)

step("icons", function()
    cv:icons { { type = "player", x = 0, z = 0, rot = 4 }, { type = "blue_marker", x = -128, z = 127, name = "tm" } }
    assert(not pcall(cv.icons, cv, { { type = "no_such_icon", x = 0, z = 0 } }))
    assert(not pcall(cv.icons, cv, { { type = "player", x = 200, z = 0 } }))
    cv:icons {}
end)

step("show/hide without player", function()
    assert(not pcall(cv.show, cv, nil))
    assert(not pcall(cv.hide, cv, "nobody"))
    cv:flush()
end)

step("item", function()
    local it = cv:item("Minimap")
    assert(it.id == "minecraft:filled_map", tostring(it.id))
    assert(it.name == "Minimap", tostring(it.name))
    local c = it:getComponent("minecraft:map_id")
    assert(tostring(c) == tostring(cv.id), "map_id " .. tostring(c))
    assert(cv:item().id == "minecraft:filled_map")
end)

local wall
step("wall", function()
    local w = mc.world("overworld")
    wall = cv:wall(w, vec(0, 100, 0), "south", 2, 1, { cv, cv2 })
    assert(wall)
    assert(not pcall(cv.wall, cv, w, vec(0, 100, 0), "up", 1, 1, { cv }))
    assert(not pcall(cv.wall, cv, w, vec(0, 100, 0), "north", 2, 2, { cv }))
    local w2 = cv2:wall(w, vec(10, 100, 0), "east", 1, 2, { cv, cv2 })
    w2:remove()
    w2:remove()
end)

mc.schedule(60, function()
    step("tick loop and remove", function()
        cv:fill(1, 1, 3, 3, 10)
        cv2:icons { { type = "frame", x = 1, z = 1 } }
        wall:remove()
        cv2:free()
        assert(not pcall(cv2.fill, cv2, 0, 0, 1, 1, 1))
        cv:free()
    end)
    print("[SMOKE] mapcanvas done")
end)
