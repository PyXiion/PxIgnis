-- Smoke-скрипт: положить в config/ignis/ пустого сервера, дождаться "[SMOKE] all done" в логе
-- ("[SMOKE] done" — конец первой фазы, дальше проверки с сущностями и перезагрузкой идут по таймерам).
-- Каждая строка — OK или FAIL <причина>. Дополняется по мере выполнения пакетов FIX-SPEC.
local function step(name, fn)
    local ok, err = pcall(fn)
    print("[SMOKE] " .. name .. ": " .. (ok and "OK" or ("FAIL " .. tostring(err))))
end
local function fails(fn, ...) return not pcall(fn, ...) end

-- Пакеты (smoke/packages): грузятся до обычных скриптов, зависимости первыми, на каждой перезагрузке заново.
step("packages: order and pkg: require", function()
    assert(rawget(_G, "SMOKE_PKG_ORDER") == "alpha,beta,", tostring(rawget(_G, "SMOKE_PKG_ORDER")))
    local beta = require("pkg:smoke_beta")
    assert(beta.alpha == "alpha" and beta.helper == "helper-of-beta" and beta.same)
    assert(require("pkg:smoke_alpha") == require("pkg:smoke_alpha"))
end)
step("packages: namespaces are closed", function()
    local ok, err = pcall(require, "smoke:thing")
    assert(not ok and tostring(err):find("unknown namespace", 1, true), tostring(err))
    local ok2, err2 = pcall(require, "pkg:smoke_missing")
    assert(not ok2 and tostring(err2):find("not installed", 1, true), tostring(err2))
    local ok3, err3 = pcall(require, "ext:smoke_missing")
    assert(not ok3 and tostring(err3):find("plugin 'smoke_missing' is not installed", 1, true), tostring(err3))
end)

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

-- Модель событий: таблица события, приоритеты, проверка имён, свои события, отмена (в том числе падением
-- обработчика), watchdog.
local function eventPhase(w)
    local order, got = {}, nil
    local ids = {
        mc.on("entity.spawn", function(e) order[#order + 1] = "low"; got = e end, { priority = "low" }),
        mc.on("entity.spawn", function(e) order[#order + 1] = "high" end, { priority = "high" }),
    }
    local pig
    step("events: spawn passes an event table", function()
        pig = w:spawn("minecraft:pig", vec(6, 80, 6), {})
        assert(got and got.name == "entity.spawn" and got.entity, "no event table")
    end)
    step("events: priority order", function() assert(order[1] == "high" and order[2] == "low", table.concat(order, ",")) end)
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
    -- e.amount is writable; the value read back after dispatch is the damage (<= 0 drops the hit)
    local function hurtAmount(handler, dmg)
        local mob = w:spawn("minecraft:pig", vec(7, 80, 7), {})
        local id = mc.on("entity.hurt", handler)
        local before = mob.health
        mob:damage(dmg)
        mc.off(id)
        local taken = before - mob.health
        mob:remove()
        return taken
    end
    step("events: hurt amount halved", function()
        local taken = hurtAmount(function(e) e.amount = e.amount / 2 end, 4)
        assert(math.abs(taken - 2) < 0.01, "took " .. taken)
    end)
    step("events: hurt amount 0 is a cancel", function()
        local taken = hurtAmount(function(e) e.amount = 0 end, 4)
        assert(taken == 0, "took " .. taken)
    end)
    step("events: later handlers see the updated amount", function()
        local seen
        local id = mc.on("entity.hurt", function(e) seen = e.amount end, { priority = "low" })
        local taken = hurtAmount(function(e) e.amount = 3 end, 4)
        mc.off(id)
        assert(seen == 3 and math.abs(taken - 3) < 0.01, tostring(seen) .. " / " .. taken)
    end)
    step("item data: round trip", function()
        local item = mc.createItem("minecraft:stick", { data = { flag = true, off = false, n = 7, d = 1.5, s = "x",
            list = { 1, 2, "three" }, nested = { a = { b = { true } } } } })
        assert(item:getData("flag") == true and item:getData("off") == false, "bool")
        assert(item:getData("n") == 7 and item:getData("d") == 1.5 and item:getData("s") == "x", "scalars")
        local l = item:getData("list")
        assert(#l == 3 and l[1] == 1 and l[3] == "three", "list")
        assert(item:getData("nested").a.b[1] == true, "nested")
        assert(item:getData("missing") == nil, "missing")
        item:setData("n", nil)
        assert(item:getData("n") == nil, "removed")
        item:setData("t", { k = { 1.5 } })
        assert(item:getData("t").k[1] == 1.5, "set table")
    end)
    step("item data: live on equipment, setItem persists", function()
        local z = w:spawn("minecraft:zombie", vec(8, 80, 8), {})
        z.mainhand = mc.createItem("minecraft:stick", { data = { id = 1 } })
        local live = z.mainhand
        live:setData("id", 2)
        assert(z.mainhand:getData("id") == 2, "wrapper is not live")
        local copy = live:copy()
        copy:setData("id", 3)
        assert(z.mainhand:getData("id") == 2, "copy leaked")
        z.mainhand = copy
        assert(z.mainhand:getData("id") == 3, "setItem lost data")
        z:remove()
    end)
    step("components: generic round trip", function()
        local item = mc.createItem("minecraft:stick")
        item:setComponent("minecraft:max_stack_size", 16)
        assert(item:getComponent("minecraft:max_stack_size") == 16, "int")
        item:setComponent("enchantment_glint_override", true)
        assert(item:getComponent("minecraft:enchantment_glint_override") == true, "bool")
        item:setComponent("minecraft:lore", { "a", "b" })
        local l = item:getComponent("minecraft:lore")
        assert(type(l) == "table" and #l == 2, "list")
        item:setComponent("minecraft:lore", {})
        assert(#item:getComponent("minecraft:lore") == 0, "empty list")
        item:removeComponent("minecraft:lore")
        assert(item:getComponent("minecraft:lore") == nil, "removed")
        item:setComponent("minecraft:max_stack_size", nil)
        assert(item:getComponent("minecraft:max_stack_size") == 64 or item:getComponent("minecraft:max_stack_size") == nil, "nil")
        item:removeComponent("minecraft:max_stack_size")
        assert(item:getComponent("minecraft:max_stack_size") == nil, "explicit removal masks the default")
        item:resetComponent("minecraft:max_stack_size")
        assert(item:getComponent("minecraft:max_stack_size") == 64, "reset restores the default")
        assert(fails(function() item:getComponent("minecraft:nope") end), "unknown id")
        local ok, err = pcall(item.setComponent, item, "minecraft:max_stack_size", "x")
        assert(not ok and tostring(err):find("max_stack_size", 1, true), tostring(err))
    end)
    step("components: createItem components and shortcuts", function()
        local item = mc.createItem("minecraft:stick", {
            item_model = "minecraft:diamond", tooltip_style = "pxrp:fancy", rarity = "epic", item_name = "Rod",
            glint = true, max_stack_size = 8, use_cooldown = { seconds = 1.5, group = "pxrp:rod" },
            custom_model = { floats = { 1, 2.5 }, flags = { true, false }, strings = { "s" }, colors = { 16711680 } },
            components = { ["minecraft:max_damage"] = 100 },
        })
        assert(item.item_model == "minecraft:diamond", tostring(item.item_model))
        assert(item.tooltip_style == "pxrp:fancy" and item.rarity == "epic" and item.item_name == "Rod", "strings")
        assert(item.glint == true and item.max_stack_size == 8, "glint/max")
        assert(item.use_cooldown.seconds == 1.5 and item.use_cooldown.group == "pxrp:rod", "cooldown")
        local m = item.custom_model
        assert(#m.floats == 2 and m.floats[2] == 2.5 and m.flags[1] == true and m.flags[2] == false, "cmd lists")
        assert(m.strings[1] == "s" and m.colors[1] == 16711680, "cmd strings/colors")
        assert(item.custom_model_data == 1, "first float")
        assert(item:getComponent("minecraft:max_damage") == 100, "components table")
        item.glint = nil
        assert(item.glint == nil, "glint reset")
        item.use_cooldown = nil
        assert(item.use_cooldown == nil, "cooldown removed")
        item.custom_model = nil
        assert(item.custom_model == nil, "cmd removed")
        item.item_model = nil
        assert(item.item_model == "minecraft:stick", "model reset to default: " .. tostring(item.item_model))
        assert(fails(function() item.use_cooldown = { seconds = 0 } end), "zero seconds")
    end)
    step("text: component table", function()
        local s = mc.decodeText({ text = "x", font = "pxrp:hud", color = "#FF0000", shadow_color = "#80000000",
            bold = true, extra = { { text = "y", shadow_color = 0 }, "z" } })
        assert(s == "xyz", s)
        assert(mc.decodeText("plain") == "plain", "string")
    end)
    step("text: invalid table is an error", function()
        local ok, err = pcall(mc.decodeText, { text = "x", color = "not a colour", bold = "yes" })
        assert(not ok and tostring(err):find("decodeText", 1, true), tostring(err))
        assert(fails(mc.decodeText, 5), "number accepted")
    end)
    step("world: isSolid / isPassable / hasBlockTag / breakBlock / setBreakProgress", function()
        local p = vec(20, 90, 20)
        w:setBlock(p, "minecraft:stone")
        assert(w:isSolid(p) and not w:isPassable(p), "stone")
        w:setBlock(p, "minecraft:air")
        assert(not w:isSolid(p) and w:isPassable(p), "air")
        w:setBlock(p, "minecraft:ladder")
        assert(w:hasBlockTag(p, "minecraft:climbable") and w:hasBlockTag(p, "#climbable"), "ladder tag")
        assert(not w:hasBlockTag(p, "minecraft:leaves"), "ladder is not leaves")
        w:setBlock(p, "minecraft:stone")
        w:setBreakProgress(p, 5)
        w:setBreakProgress(p, nil)
        assert(fails(function() w:setBreakProgress(p, 10) end), "stage 10")
        assert(w:breakBlock(p, { drops = false }) == true, "break")
        assert(w:getBlock(p) == "minecraft:air", "not air after break")
        assert(w:breakBlock(p) == false, "air breaks to false")
        w:setBlock(p, "minecraft:dirt")
        assert(w:breakBlock(p, { effects = false }) and w:getBlock(p) == "minecraft:air", "quiet break")
    end)
    step("entity: onGround", function()
        local pig = w:spawn("minecraft:pig", vec(24, 200, 24), {})
        assert(pig.onGround == false, "falling pig is on the ground")
        pig:remove()
    end)
    step("events: builtin names cannot be emitted", function() assert(fails(mc.emit, "entity.spawn")) end)
    step("events: a pre-1.0 name is an error", function()
        local ok, err = pcall(mc.on, "entity_spawn", function() end)
        assert(not ok and tostring(err):find("Did you mean 'entity.spawn'", 1, true), tostring(err))
    end)
    step("events: a custom name without a namespace is an error", function()
        local ok, err = pcall(mc.on, "smoke_custom", function() end)
        assert(not ok and tostring(err):find("must be namespaced", 1, true), tostring(err))
        assert(fails(mc.emit, "smoke_custom"), "emit accepted it")
    end)
    step("events: a namespaced custom event round-trips", function()
        local seen
        local id = mc.on("smoke:custom", function(a, b) seen = a .. b end)
        assert(mc.emit("smoke:custom", "x", 1) == true, "emit reported a cancel")
        mc.off(id)
        assert(seen == "x1", tostring(seen))
    end)
    step("events: region:on follows the same name rule", function()
        local r = w:createRegion(vec(20, 70, 20), vec(24, 74, 24))
        r:off(r:on("enter", function() end))
        r:off(r:on("smoke:custom", function() end))
        local ok, err = pcall(r.on, r, "entity_enter", function() end)
        assert(not ok and tostring(err):find("unknown event 'entity_enter'", 1, true), tostring(err))
        assert(fails(r.on, r, "smoke_custom", function() end), "bare custom name accepted")
        r:destroy()
    end)
    step("events: region:emit is local, positional and cancellable", function()
        local r = w:createRegion(vec(20, 70, 20), vec(24, 74, 24))
        local other = w:createRegion(vec(30, 70, 30), vec(34, 74, 34))
        local calls, otherCalls, globalCalls = {}, 0, 0
        local gid = mc.on("smoke:ping", function() globalCalls = globalCalls + 1 end)
        other:on("smoke:ping", function() otherCalls = otherCalls + 1 end)
        r:on("smoke:ping", function(a, b) calls[#calls + 1] = "low" end)
        r:on("smoke:ping", function(a, b)
            calls[#calls + 1] = "high:" .. tostring(a) .. "," .. tostring(b)
            return false
        end, { priority = "high" })
        r:on("smoke:ping", function() calls[#calls + 1] = "audit" end, { priority = "lowest", receiveCancelled = true })
        local ok, err = pcall(function() return r:emit("smoke:ping", "x", 2) end)
        mc.off(gid)
        assert(ok, tostring(err))
        assert(err == false, "emit returned " .. tostring(err))
        assert(table.concat(calls, "|") == "high:x,2|audit", table.concat(calls, "|"))
        assert(otherCalls == 0 and globalCalls == 0, "leaked to " .. otherCalls .. "/" .. globalCalls)
        assert(other:emit("smoke:quiet") == true, "no handlers must return true")
        assert(fails(r.emit, r, "enter"), "built-in name accepted")
        assert(fails(r.emit, r, "smoke_ping"), "bare name accepted")
        other:destroy()
        r:destroy()
        assert(fails(r.emit, r, "smoke:ping"), "emit after destroy accepted")
    end)
    pig:remove()

    step("ignis: /ignis status", function() assert(mc.execute("ignis status"), "command failed") end)

    -- A cycle in a task must not hold the server: the watchdog stops it, and the next task still runs.
    mc.schedule(1, function() while true do end end)
    mc.schedule(2, function() step("watchdog: runaway task was stopped", function() end) end)
end

-- Диалоги: библиотека грузится, сырой API на месте (показ требует игрока, поэтому только наличие и ошибки).
local function dialogPhase()
    step("dialog: library loads", function()
        local dialog = require "core:dialog"
        assert(type(dialog.show) == "function" and type(dialog.button) == "function")
        assert(fails(dialog.show, nil, { title = "x" }), "show without a player")
    end)
    step("dialog: warn", function() mc.warn("smoke", "expected warning") end)

    -- Every shape the library can emit must decode through the vanilla codec (no player needed).
    local n = 0
    local fake = { uuid = "smoke-dialog", name = "smoke" }
    function fake:showDialog(spec) mc.decodeDialog(spec); n = n + 1 end
    function fake:closeDialog() end
    function fake:runCommand() return true, 1 end
    local dialog = require "core:dialog"
    local function ok(name, spec)
        step("dialog shape: " .. name, function() dialog.show(fake, spec) end)
    end
    local noop = function() end
    local item = mc.createItem("minecraft:diamond_sword", { name = "S" })
    local inputs = {
        dialog.text("t", "T", { max = 10, initial = "x", width = 200 }),
        dialog.text("ml", "ML", { max = 100, lines = 3, height = 60 }),
        dialog.text("hl", "HL", { hideLabel = true }),
        dialog.number("n", "N", 0, 10, { step = 2, initial = 4, format = "options.generic_value", width = 150 }),
        dialog.number("n2", "N2", 0, 1),
        dialog.option("o", "O", { { "a", "A" }, { "b", "B" } }, { initial = "b", width = 100, hideLabel = true }),
        dialog.bool("b", "B", { initial = true }),
        dialog.bool("b2", "B2"),
    }
    local body = { "text", dialog.item(item, "desc", { tooltip = false, decorations = false, width = 32, height = 32 }),
        dialog.item("minecraft:apple"), dialog.item(item) }
    ok("notice, no buttons", { title = "t" })
    ok("notice, no buttons + inputs/body", { title = "t", externalTitle = "e", body = body, inputs = inputs })
    ok("notice, one button, no escape", { title = "t", canEscape = false, buttons = { dialog.button("b", noop) } })
    ok("one button + escape", { title = "t", buttons = { dialog.button("b", noop, { tooltip = "tip", width = 120 }) } })
    ok("one button + inputs", { title = "t", inputs = inputs, body = body, buttons = { dialog.button("b", noop) } })
    ok("many buttons, columns", { title = "t", columns = 2, buttons = {
        dialog.button("a", noop, { after = "keep" }), dialog.button("b", noop, { after = "close" }),
        dialog.command("c", "say hi"), dialog.link("l", "https://example.com"), dialog.copy("y", "text") } })
    ok("all wait", { title = "t", buttons = { dialog.button("a", noop), dialog.command("b", "say hi") } })
    ok("all keep", { title = "t", buttons = { dialog.button("a", noop, { after = "keep" }) , dialog.button("b", noop, { after = "keep" }) } })
    ok("all close", { title = "t", buttons = { dialog.button("a", noop, { after = "close" }), dialog.button("b", noop, { after = "close" }) } })
    ok("no escape, many buttons + inputs", { title = "t", canEscape = false, inputs = inputs, buttons = {
        dialog.button("a", noop), dialog.link("l", "https://example.com") } })
    ok("link only", { title = "t", canEscape = false, buttons = { dialog.link("l", "https://example.com") } })
    step("dialog: raw rejects bad type", function() assert(fails(mc.decodeDialog, { type = "minecraft:nonsense", title = "x" })) end)
    step("dialog: shapes shown", function() assert(n == 11, n) end)
end

-- Сущности: чанк удерживается forceload, а проверки идут через 10 секунд, когда чанк точно принимает сущности.
local function entityPhase(w)
    particlePhase(w)
    eventPhase(w)
    dialogPhase()
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
