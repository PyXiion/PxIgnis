require "format"
require "simple"


function fart()
    local pos = player.pos
    local dir = player.bodyDir
    local now = mc.time()
    local last = player.data.lastFart or 0

    if now - last < 10 then
        mc.broadcast("Подожди " .. (10 - (now - last)) .. " секунд!")
        return
    end

    player.data.lastFart = now

    broadcastFormat "*{p.name} пёрнул*" {p=player}
    mc.particle("minecraft:gust", pos.x - dir.x * 0.5, pos.y + 0.6, pos.z - dir.z * 0.5, player.world)
    mc.playSound("minecraft:entity.slime.squish", pos.x, pos.y, pos.z, player.world, 10, 0.1)
end

function kill(target)
    broadcastFormat "*{p.name} убил(а) {t.name}*" {p=player, t=target}
end

function coins()
    local bal = player.data.coins or 0
    mc.broadcast("У тебя " .. bal .. " монет")
end

function giveCoins()
    player.data.coins = (player.data.coins or 0) + 10
    mc.broadcast("+10 монет! Теперь у тебя " .. player.data.coins .. " монет")
end

function payCoins(target)
    local amount = 10
    local bal = player.data.coins or 0

    if bal < amount then
        mc.broadcast("Недостаточно монет! У тебя только " .. bal)
        return
    end

    player.data.coins = bal - amount
    target.data.coins = (target.data.coins or 0) + amount
    mc.broadcast(player.name .. " передал " .. amount .. " монет " .. target.name)
end

function eventStats()
    local total = (mc.data.totalEvents or 0) + 1
    mc.data.totalEvents = total
    mc.broadcast("Серверное событие #" .. total .. " запущено!")
end


register("fart", {}, fart)
register("rp kill", {"target"}, kill, "rp.kill")
register("coins", {}, coins)
register("rp coins give", {}, giveCoins)
register("rp pay", {"target"}, payCoins)
register("rp event", {}, eventStats)