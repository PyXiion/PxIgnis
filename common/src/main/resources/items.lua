local items = {}

local _templates = {}
local _templatesById = {}
local _tickCount = 0
local _inventoryTickCount = 0
local INVENTORY_SIZE = 41

local function normalizeId(id)
    if id:find(":") then return id end
    return "minecraft:" .. id
end

local function modelKey(id, modelData)
    return id .. "#" .. (modelData and tostring(modelData) or "_")
end

local TemplateMethods = {}

function TemplateMethods:make(count)
    local opts = { count = count or 1 }
    if self.name then opts.name = self.name end
    if self.lore then opts.lore = self.lore end
    if self.modelData ~= nil then opts.custom_model_data = self.modelData end
    if self.unbreakable then opts.unbreakable = true end
    return mc.createItem(self.id, opts)
end

function TemplateMethods:matches(item)
    return items.is(item, self)
end

function TemplateMethods:has(player)
    if not player then return false end
    for i = 0, INVENTORY_SIZE - 1 do
        local item = player:getItem(i)
        if item and self:matches(item) then
            return true
        end
    end
    return false
end

function items.define(opts)
    if type(opts) ~= "table" then
        error("items.define: expected a table", 2)
    end
    if type(opts.id) ~= "string" or opts.id == "" then
        error("items.define: id must be a non-empty string", 2)
    end

    local normalizedId = normalizeId(opts.id)
    local key = opts.key or (opts.id .. "#" .. (opts.modelData and tostring(opts.modelData) or "nil"))

    if _templates[key] then
        error("items.define: duplicate key '" .. key .. "'", 2)
    end

    local template = {}
    for k, v in pairs(opts) do
        template[k] = v
    end

    template.key = key
    template.id = normalizedId

    setmetatable(template, { __index = TemplateMethods })

    _templates[key] = template
    _templatesById[modelKey(normalizedId, opts.modelData)] = template

    if opts.onTick then
        _tickCount = _tickCount + 1
    end
    if opts.onInventoryTick then
        _inventoryTickCount = _inventoryTickCount + 1
    end

    return template
end

function items.is(item, template)
    if not item or not template then return false end
    if item.id ~= template.id then return false end
    if template.modelData == nil then return true end
    return item.custom_model_data == template.modelData
end

function items.has(player, template)
    if not player or not template then return false end
    return template:has(player)
end

function items.find(item)
    if not item then return nil end
    if type(item) == "string" then
        return _templates[item]
    end
    local tpl = _templatesById[modelKey(item.id, item.custom_model_data)]
    if tpl then return tpl end
    return _templatesById[item.id .. "#_"]
end

mc.on("player_use_item", function(player, hand, item, itemId)
    local tpl = items.find(item)
    if tpl and tpl.onUse then
        return tpl.onUse(player, hand, item, tpl)
    end
end)

mc.on("player_attack_entity", function(player, target)
    local item = player.mainhand
    if not item then return end
    local tpl = items.find(item)
    if tpl and tpl.onAttack then
        return tpl.onAttack(player, target, item, tpl)
    end
end)

mc.on("player_consume_item", function(player, item)
    local tpl = items.find(item)
    if tpl and tpl.onConsume then
        return tpl.onConsume(player, item, tpl)
    end
end)

mc.on("player_pickup_item", function(player, item, count)
    local tpl = items.find(item)
    if tpl and tpl.onPickup then
        return tpl.onPickup(player, item, count, tpl)
    end
end)

mc.on("player_interact_entity", function(player, target, hand)
    local item
    if hand and hand:lower() == "off" then
        item = player.offhand
    else
        item = player.mainhand
    end
    if not item then return end
    local tpl = items.find(item)
    if tpl and tpl.onInteractEntity then
        return tpl.onInteractEntity(player, target, hand, item, tpl)
    end
end)

mc.on("tick", function()
    if _tickCount == 0 and _inventoryTickCount == 0 then return end
    for _, p in ipairs(mc.players) do
        if _tickCount > 0 then
            local mh = p.mainhand
            if mh then
                local tpl = items.find(mh)
                if tpl and tpl.onTick then
                    tpl.onTick(p, mh, "main", tpl)
                end
            end
            local oh = p.offhand
            if oh then
                local tpl = items.find(oh)
                if tpl and tpl.onTick then
                    tpl.onTick(p, oh, "off", tpl)
                end
            end
        end
        if _inventoryTickCount > 0 then
            for i = 0, INVENTORY_SIZE - 1 do
                local item = p:getItem(i)
                if item then
                    local tpl = items.find(item)
                    if tpl and tpl.onInventoryTick then
                        tpl.onInventoryTick(p, item, i, tpl)
                    end
                end
            end
        end
    end
end)

return items
