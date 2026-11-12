-- Smoke-скрипт: положить в config/ignis/ пустого сервера, дождаться "[SMOKE] all done" в логе
-- ("[SMOKE] done" — конец первой фазы, дальше проверки с сущностями и перезагрузкой идут по таймерам).
-- Каждая строка — OK или FAIL <причина>. Дополняется по мере выполнения пакетов FIX-SPEC.
local function step(name, fn)
    local ok, err = pcall(fn)
    print("[SMOKE] " .. name .. ": " .. (ok and "OK" or ("FAIL " .. tostring(err))))
end
local function fails(fn, ...) return not pcall(fn, ...) end

-- команды и поведения регистрируются только при загрузке скрипта
register("smokecmd", function(ctx) mc.data.smoke_cmd_ran = true end)
mc.registerBehaviour("smoke_ai", function(self, state) end)
register("smokechoice <m:choice=да,нет>", function(ctx, m) end)

-- Частицы: particle(), world:emit/emitMany и core:particles.
local function particlePhase(w)
    local p
    step("particle: id", function()
        p = particle("end_rod")
        assert(p.id == "minecraft:end_rod", tostring(p.id))
    end)
    step("particle: data", function() particle("dust", { color = { 255, 0, 0 }, scale = 2 }) end)
    step("emit: options", function() w:emit(p, vec(0, 80, 0), { count = 3, spread = 0.5, speed = 0.1, alwaysShow = true }) end)
    step("emit: spread as vec", function() w:emit(p, vec(0, 80, 0), { count = 2, spread = vec(1, 2, 3) }) end)
    step("emit: velocity", function() w:emit(p, vec(0, 80, 0), { velocity = vec(0, 1, 0) }) end)
    step("emit: empty viewers", function() w:emit(p, vec(0, 80, 0), { viewers = {} }) end)
    step("emitMany", function() w:emitMany(p, { vec(0, 80, 0), vec(1, 80, 0) }) end)
    step("particle: unknown id", function() assert(fails(particle, "typo")) end)
    step("particle: data required", function() assert(fails(particle, "dust")) end)
    step("emit: id string rejected", function() assert(fails(function() w:emit("end_rod", vec(0, 80, 0)) end)) end)
    step("emit: velocity with count", function()
        assert(fails(function() w:emit(p, vec(0, 80, 0), { velocity = vec(0, 1, 0), count = 2 }) end))
    end)
    step("emit: count 0", function() assert(fails(function() w:emit(p, vec(0, 80, 0), { count = 0 }) end)) end)
    step("emit: bad viewers", function() assert(fails(function() w:emit(p, vec(0, 80, 0), { viewers = { 1 } }) end)) end)
    step("emit: unknown option", function() assert(fails(function() w:emit(p, vec(0, 80, 0), { bogus = 1 }) end)) end)

    local fx = require "core:particles"
    local h
    step("fx: play", function()
        h = fx.play(fx.wait(2), { at = vec(0, 80, 0), world = w })
        assert(not h.done, "done immediately")
        mc.schedule(6, function()
            step("fx: finished", function() assert(h.done, "not done after 6 ticks") end)
        end)
    end)
    step("fx: loop stop", function()
        local loop = fx.play(fx.loop(fx.circle { particle = p }), { at = vec(0, 80, 0), world = w })
        assert(loop:stop() == true)
        assert(loop.done)
    end)
end

-- Модель событий: таблица события, приоритеты, старые имена, отмена (в том числе падением обработчика), watchdog.
local function eventPhase(w)
    local order, got, legacy = {}, nil, nil
    local ids = {
        mc.on("entity.spawn", function(e) order[#order + 1] = "low"; got = e end, { priority = "low" }),
        mc.on("entity.spawn", function(e) order[#order + 1] = "high" end, { priority = "high" }),
        mc.on("entity_spawn", function(entity) legacy = entity end),
    }
    local pig
    step("events: spawn passes an event table", function()
        pig = w:spawn("minecraft:pig", vec(6, 80, 6), {})
        assert(got and got.name == "entity.spawn" and got.entity, "no event table")
    end)
    step("events: priority order", function() assert(order[1] == "high" and order[2] == "low", table.concat(order, ",")) end)
    step("events: old name gets positional args", function() assert(legacy and legacy.type == "minecraft:pig", tostring(legacy)) end)
    for _, id in ipairs(ids) do mc.off(id) end

    local function hurtWith(handler)
        local id = mc.on("entity.hurt", handler)
        local before = pig.health
        pig:damage(1)
        mc.off(id)
        return before, pig.health
    end
    step("events: e:cancel() stops the damage", function()
        local before, after = hurtWith(function(e) e:cancel("test") end)
        assert(after == before, before .. " -> " .. after)
    end)
    step("events: a failing handler cancels the event", function()
        local before, after = hurtWith(function(e) error("smoke: expected failure") end)
        assert(after == before, before .. " -> " .. after)
    end)
    step("events: no handler, damage applies", function()
        local before = pig.health
        pig:damage(1)
        assert(pig.health < before, "damage not applied")
    end)
    step("events: builtin names cannot be emitted", function() assert(fails(mc.emit, "entity.spawn")) end)
    pig:remove()

    step("ignis: /ignis status", function() assert(mc.execute("ignis status"), "command failed") end)

    -- A cycle in a task must not hold the server: the watchdog stops it, and the next task still runs.
    mc.schedule(1, function() while true do end end)
    mc.schedule(2, function() step("watchdog: runaway task was stopped", function() end) end)
end

-- Сущности: чанк удерживается forceload, а проверки идут через 10 секунд, когда чанк точно принимает сущности.
local function entityPhase(w)
    particlePhase(w)
    eventPhase(w)
    local mob
    w:setBlock(vec(0, 79, 0), "minecraft:stone")
    step("spawn", function() mob = w:spawn("minecraft:zombie", vec(0, 80, 0), {}); assert(mob) end)
    step("entity type id", function() assert(mob.type == "minecraft:zombie", mob.type) end)
    step("invulnerable", function() mob.invulnerable = true; assert(mob.invulnerable == true) end)
    step("tags", function() mob.tags.smoke = true; assert(mob.tags.smoke, "tag missing") end)
    step("entity: rotation, gravity, noAi, scale, remove", function()
        local pig = w:spawn("minecraft:pig", vec(3, 80, 3), {})
        assert(pig, "no pig")
        pig.yaw = 90; pig.pitch = -20
        assert(math.abs(pig.yaw - 90) < 1e-3, "yaw=" .. tostring(pig.yaw))
        assert(math.abs(pig.pitch + 20) < 1e-3, "pitch=" .. tostring(pig.pitch))
        pig.noGravity = true; assert(pig.noGravity == true, "noGravity")
        pig.noAi = true; assert(pig.noAi == true, "noAi")
        pig.scale = 0.5; assert(math.abs(pig.scale - 0.5) < 1e-6, "scale=" .. tostring(pig.scale))
        pig.velocity = vec(0, 0.5, 0)
        assert(math.abs(pig.velocity.y - 0.5) < 1e-6 and pig.velocity.x == 0, "velocity=" .. tostring(pig.velocity))
        pig:remove()
        assert(pig.removed, "not removed")
    end)
    step("lightning", function() w:strike(vec(5, 80, 5)) end)
    step("registry: spawn typo", function() assert(fails(w.spawn, w, "zombi", vec(0, 80, 0))) end)
    step("registry: hologram", function() local h = w:spawnHologram(vec(0, 82, 0), "x"); assert(h); h:destroy() end)

    -- 9. голограммы
    local holo = w:spawnHologram(vec(0, 83, 0), "smoke-holo", { opacity = 200, shadow = true })
    step("hologram: options go through setters", function()
        assert(holo.opacity == 200, "opacity=" .. tostring(holo.opacity))
        assert(holo.shadow == true, "shadow")
    end)
    step("hologram: manager hands out the same table", function() assert(mc.holograms()[1] == holo) end)
    step("hologram: failed options leave nothing behind", function()
        local before = #mc.holograms()
        assert(fails(w.spawnHologram, w, vec(0, 84, 0), "x", { billboard = "typo" }), "typo accepted")
        assert(#mc.holograms() == before, "leaked " .. (#mc.holograms() - before))
    end)
    local woken = false
    step("async: resolve does not run the waiter inline", function()
        local async = require("async")
        local pr = async.promise()
        local co = coroutine.create(function() pr:wait(); woken = true end)
        assert(coroutine.resume(co))
        pr:resolve(1)
        assert(not woken, "waiter ran inside resolve")
    end)
    mc.schedule(2, function()
        step("async: the waiter runs from the tick", function() assert(woken, "waiter never resumed") end)
    end)
    mc.schedule(10, function()
        step("hologram: exactly one tagged display", function()
            local ok, n = mc.execute("execute if entity @e[type=text_display,tag=pxignis_hologram]")
            assert(ok and n == 1, tostring(ok) .. " / " .. tostring(n))
        end)
        step("hologram: text survives edit", function() holo.text = "x"; assert(holo.text == "x") end)
    end)

    -- 6. перезагрузка: ИИ моба и ванильный /reload
    step("reload: setAI", function()
        mob:setAI("smoke_ai")
        assert(mob.aiActive, "aiActive")
        assert(mob.tags["pxrp_behaviour:smoke_ai"], "tag missing")
    end)
    mc.data.smoke_mob = mob.uuid
    mc.data.smoke_cmd_ran = false
    mc.schedule(40, function()
        mc.data.smoke_phase = 2
        mc.execute("ignis reload")
    end)
end

-- async.parallel: вызовы приостанавливают корутину, а yield не проходит через pcall Lua-функции,
-- поэтому каждая проверка идёт отдельной задачей с защищённым wait.
local function parallelPhase(tag)
    local async = require("async")
    local function astep(name, fn)
        local ok, err = async.task(fn):wait(true)
        print("[SMOKE] " .. tag .. name .. ": " .. (ok and "OK" or ("FAIL " .. tostring(err))))
    end
    async.task(function()
        astep("parallel: sum", function()
            local sum = async.parallel(function(n)
                local s = 0
                for i = 1, n do s = s + i end
                return s
            end)
            local r = sum(1000000)
            assert(r == 500000500000, "sum=" .. tostring(r))
        end)
        astep("parallel: frozen grid by reference", function()
            local grid = table.freeze({ 1, 2, 3, 4 })
            local size = async.parallel(function() return #grid end)
            assert(size() == 4, "size")
            assert(table.isfrozen(grid), "not frozen")
        end)
        astep("parallel: mc is unavailable", function()
            assert(fails(async.parallel, function() return mc.players end))
        end)
        astep("parallel: vector", function()
            local add = async.parallel(function(v) return v.x + v.y + v.z end)
            assert(add(vec(1, 2, 3)) == 6, "sum of components")
        end)
    end)
end

mc.on("server_start", function()
    mc.execute("forceload add 0 0")
    local w
    step("world", function() w = mc.world("overworld"); assert(w, "no world") end)
    step("time get/set", function() local t = w.time; w.time = 6000; assert(math.floor(w.time) == 6000, "time=" .. w.time); w.time = t end)
    step("weather", function()
        w.raining = false; w.thundering = false
        w.thundering = true; assert(w.raining and w.thundering, "thunder must imply rain")
        w.raining = false; assert(not w.raining and not w.thundering, "no rain, no thunder")
        w.raining = true; assert(w.raining and not w.thundering)
        w.raining = false
    end)
    step("bossbar", function() local b = mc.createBossBar("t", "red", "progress"); assert(b) end)
    step("structure-missing", function() local ok = pcall(mc.loadStructure, "nope:nope"); assert(not ok) end)

    -- 1. реестры: опечатка — ошибка, а не свинья/воздух
    step("registry: fill typo keeps blocks", function()
        w:setBlock(vec(10, 80, 10), "minecraft:dirt")
        assert(fails(w.fill, w, vec(10, 80, 10), vec(11, 80, 11), "stoen"))
        assert(w:getBlock(vec(10, 80, 10)) == "minecraft:dirt")
        w:setBlock(vec(10, 80, 10), "minecraft:air")
    end)
    step("registry: setBlock typo", function() assert(fails(w.setBlock, w, vec(10, 80, 10), "stoen")) end)

    -- 4. вектор
    step("vector: zero is not shared", function()
        local a = vec(1, 1, 1)
        local z = a - a
        z.x = 5
        assert((a - a).x == 0, "ZERO shared")
        assert(vec(0, 0, 0).x == 0)
    end)
    step("vector: unknown field", function() local v = vec(1, 2, 3); assert(fails(function() v.w = 1 end)) end)
    step("vector: eq", function() assert(vec(1, 2, 3) == vec(1, 2, 3)); assert(vec(1, 2, 3) ~= vec(4, 5, 6)) end)

    -- 7. песочница
    step("sandbox: string.dump", function() assert(string.dump == nil) end)
    step("sandbox: load b", function() assert(fails(load, "return 1", "x", "b")) end)
    step("sandbox: load binary header", function() assert(load("\27Lua", "x") == nil) end)
    step("sandbox: load t env", function() assert(load("return x", "x", "t", { x = 7 })() == 7) end)
    step("sandbox: loadStructureFile escape", function() assert(fails(mc.loadStructureFile, "../../server.properties")) end)

    -- 16. команды: choice — литералы Brigadier
    step("commands: choice option is parsed", function()
        local _, res = mc.execute("smokechoice да")
        assert(not tostring(res):find("Unknown or incomplete"), tostring(res))
    end)
    step("commands: choice rejects other words", function()
        local ok, res = mc.execute("smokechoice maybe")
        assert(not ok and tostring(res):find("Incorrect argument"), tostring(res))
    end)

    mc.schedule(200, function() entityPhase(w) end)
    -- 17. строгие глобальные: чтение необъявленного — ошибка, rawget — проба
    step("strict globals: undefined read fails", function() assert(fails(function() return smoke_undefined_global end)) end)
    step("strict globals: rawget probe", function() assert(rawget(_G, "smoke_undefined_global") == nil) end)
    step("builtin modules load", function() for _, m in ipairs({ "format", "simple", "chestgui", "items" }) do require("core:" .. m) end end)

    parallelPhase("")
    print("[SMOKE] done")
end)

mc.on("init", function()
    local phase = mc.data.smoke_phase
    if phase == 2 then
        step("reload: ai survives /ignis reload", function()
            local mob = mc.getEntity(mc.data.smoke_mob)
            local _, zombies = mc.execute("execute if entity @e[type=zombie]")
            assert(mob, "mob gone (zombies in world: " .. tostring(zombies) .. ", id " .. tostring(mc.data.smoke_mob) .. ")")
            assert(mob.aiActive, "aiActive false after reload")
            assert(mob.tags["pxrp_behaviour:smoke_ai"], "tag lost")
        end)
        parallelPhase("reload 1: ")
        mc.data.smoke_phase = 3
        mc.schedule(20, function() mc.execute("reload") end)
    elseif phase == 3 then
        mc.data.smoke_phase = nil
        -- init стреляет до registerAll(): команды проверяем на следующих тиках
        mc.schedule(5, function()
            step("reload: vanilla /reload re-registers commands", function()
                local _, res = mc.execute("smokecmd")
                -- команда зарегистрирована (источник консоль, поэтому она отвечает «нужен игрок»), а не «Unknown»
                assert(not tostring(res):find("Unknown or incomplete"), "command missing: " .. tostring(res))
            end)
            step("reload: ai survives vanilla /reload", function()
                local m = mc.getEntity(mc.data.smoke_mob)
                assert(m and m.aiActive, "aiActive false")
            end)
            parallelPhase("reload 2: ")
            print("[SMOKE] all done")
        end)
    end
end)
