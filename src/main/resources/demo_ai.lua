-- PxIgnis Mob AI Demo
-- Поместите в config/ignis/demo_ai.lua и /ignis reload

-- Хелпер: найти ближайших игроков в радиусе от моба
local function nearestPlayers(mob, range)
    local result = {}
    for _, p in ipairs(mob.world.players) do
        if mob:distanceTo(p) < range then
            table.insert(result, p)
        end
    end
    return result
end

-- Хелпер: найти ближайших мобов другого типа
local function nearestMobs(mob, range)
    local result = {}
    for _, e in ipairs(mob.world:getEntities(mob.pos, range)) do
        if e.uuid ~= mob.uuid and e.health then
            table.insert(result, e)
        end
    end
    return result
end

-- ============================================================
-- 1. "guard" — патрулирует точку, атакует ближайших мобов
-- ============================================================
mc.registerBehaviour("guard", function(self, state)
    state.home = state.home or vec(self.pos)
    state.timer = (state.timer or 0) + 1

    -- Каждую секунду ищем врага
    if state.timer % 20 == 0 then
        local nearby = nearestMobs(self, 10)
        state.enemy = nil
        for _, e in ipairs(nearby) do
            if e.type ~= self.type then
                state.enemy = e
                break
            end
        end
    end

    -- Если враг есть
    if state.enemy then
        -- Не удалился ли он из мира :(
        if state.enemy.removed then
            state.enemy = nil
        else
            -- Злобно смотрим
            self:lookAt(state.enemy)
            local d = self:distanceTo(state.enemy)
            if d > 2 then
                -- Идём
                self:navigateTo(state.enemy)
            else
                -- Стоим и атакуем
                self:stopNavigation()
                self:tryAttack(state.enemy)
            end
        end
    else -- нет врага - идём домой
        local h = state.home
        local d = self.pos - h
        if d:lengthSq() > 16 then
            self:navigateTo(h.x, h.y, h.z)
            self:lookAt(h.x, h.y, h.z)
        end
    end
end)

-- ============================================================
-- 2. "pet" — следует за ближайшим игроком
-- ============================================================
mc.registerBehaviour("pet", function(self, state)
    state.owner = state.owner or nil
    state.timer = (state.timer or 0) + 1

    if state.timer % 20 == 0 then
        local nearby = nearestPlayers(self, 15)
        state.owner = #nearby > 0 and nearby[1] or nil
    end

    if state.owner then
        if state.owner.removed then
            state.owner = nil
        else
            local d = self:distanceTo(state.owner)
            if d > 3 then
                self:navigateTo(state.owner)
            else
                self:stopNavigation()
                self:lookAt(state.owner)
            end
        end
    end
end)

-- ============================================================
-- 3. "orbiter" — кружит вокруг игрока, покачиваясь по высоте
-- ============================================================
mc.registerBehaviour("orbiter", function(self, state)
    state.angle = (state.angle or math.random() * math.pi * 2) + math.random() * 0.06 + 0.03
    state.radius = state.radius or (3 + math.random() * 4)
    state.timer = (state.timer or 0) + 1

    if state.timer % 15 == 0 then
        local nearby = nearestPlayers(self, 12)
        state.center = nil
        for _, p in ipairs(nearby) do
            if not p.removed then
                state.center = p
                break
            end
        end
    end

    if state.center then
        if state.center.removed then
            state.center = nil
        else
            local bob = math.sin(self.age * 0.03) * 1.5
            local cx = state.center.pos.x + math.cos(state.angle) * state.radius
            local cz = state.center.pos.z + math.sin(state.angle) * state.radius
            local cy = state.center.pos.y + bob
            local d = self:distanceTo({x=cx, y=cy, z=cz})
            if d > 1 then
                self:navigateTo(cx, cy, cz, 1.1)
            end
            local lx = state.center.pos.x + math.cos(state.angle + 0.3) * state.radius
            local lz = state.center.pos.z + math.sin(state.angle + 0.3) * state.radius
            self:lookAt(lx, state.center.pos.y + bob + 0.5, lz)
        end
    end
end)

-- ============================================================
-- 4. "statue" — стоит, следит за игроками
-- ============================================================
mc.registerBehaviour("statue", function(self, state)
    state.timer = (state.timer or 0) + 1
    if state.timer % 10 == 0 then
        local nearby = nearestPlayers(self, 8)
        if #nearby > 0 then self:lookAt(nearby[1]) end
    end
end)

-- ============================================================
-- 5. "wander" — бесцельно бродит
-- ============================================================
mc.registerBehaviour("wander", function(self, state)
    state.timer = (state.timer or 0) + 1
    if state.timer % 60 == 0 or not self.pathFound then
        local dx = math.random(-10, 10)
        local dz = math.random(-10, 10)
        local wx = self.pos.x + dx
        local wz = self.pos.z + dz
            self:navigateTo(wx, self.pos.y, wz)
    end
end)

-- ============================================================
-- Команды для тестирования
-- ============================================================
register("spawnguard", function(ctx)
    local w = mc.world("minecraft:overworld")
    local m = w:spawn("minecraft:zombie", ctx.player.pos)
    m.customName = "Стражник"
    m.health = 40; m.maxHealth = 40
    m:setAI("guard")
    ctx.player:sendMessage("Стражник с AI 'guard'")
end, "px.ignis")

register("spawnpet", function(ctx)
    local w = mc.world("minecraft:overworld")
    local m = w:spawn("minecraft:wolf", ctx.player.pos)
    m.customName = "Питомец"
    m:setAI("pet")
    ctx.player:sendMessage("Питомец с AI 'pet'")
end, "px.ignis")

register("spawnorbiter <count:int>", function(ctx, n)
    local w = mc.world("minecraft:overworld")
    for i = 1, n do
        local m = w:spawn("minecraft:bee", ctx.player.pos)
        m.customName = "Орбитер " .. i
        m:setAI("orbiter")
    end
    ctx.player:sendMessage(n .. " пчёл с AI 'orbiter'")
end, "px.ignis")

register("spawnstatue", function(ctx)
    local w = mc.world("minecraft:overworld")
    local m = w:spawn("minecraft:zombie", ctx.player.pos)
    m.customName = "Статуя"
    m.health = 40; m.maxHealth = 40
    m.aiStepHeight = 0
    m:setAI("statue")
    ctx.player:sendMessage("Статуя с AI 'statue'")
end, "px.ignis")

register("spawnwander <type:word> [<count:int>]", function(ctx, etype, count)
    local w = mc.world("minecraft:overworld")
    count = count or 1
    for i = 1, count do
        local m = w:spawn("minecraft:" .. etype, ctx.player.pos)
        m.customName = "Бродяга " .. i
        m:setAI("wander")
    end
    ctx.player:sendMessage(count .. "x " .. etype .. " с AI 'wander'")
end, "px.ignis")

-- Спавн с произвольным поведением
register("spawnai <type:word> <behaviour:word> [<name:text>]", function(ctx, etype, behaviour, name)
    local w = mc.world("minecraft:overworld")
    local m = w:spawn("minecraft:" .. etype, ctx.player.pos)
    if name then m.customName = name end
    local ok, err = pcall(function() m:setAI(behaviour) end)
    if not ok then
        ctx.player:sendMessage("Ошибка: " .. tostring(err))
    else
        ctx.player:sendMessage(etype .. " с AI '" .. behaviour .. "'")
    end
end, "px.ignis")

-- Сбросить AI у ближайшего моба
register("clearmobai", function(ctx)
    local w = mc.world("minecraft:overworld")
    for _, e in ipairs(w:getEntities(ctx.player.pos, 5)) do
        if e.isMob and e.aiActive then
            local name = e.customName or e.type
            e:clearAI()
            ctx.player:sendMessage("AI сброшен у " .. name)
            return
        end
    end
    ctx.player:sendMessage("Мобов с AI поблизости нет")
end, "px.ignis")

-- Список ближайших мобов с AI
register("aimobs", function(ctx)
    local w = mc.world("minecraft:overworld")
    local list = ""
    for _, e in ipairs(w:getEntities(ctx.player.pos, 40)) do
        if e.isMob and e.aiActive then
            list = list .. "  " .. (e.customName or e.type) .. " (" .. e.type .. ")\n"
        end
    end
    if list == "" then
        ctx.player:sendMessage("Мобов с AI рядом нет")
    else
        ctx.player:sendMessage("Мобы с AI:\n" .. list)
    end
end, "px.ignis")