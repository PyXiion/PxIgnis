-- ==========================================================================
-- chestgui.lua — Chest GUI library for PxIgnis
-- ==========================================================================
-- Usage: local chestgui = require "core:chestgui"
--
-- Creates a chest-like GUI using mc.createInventory + container primitives.
--
-- Click callback (per button, see gui:button) receives:
--   function(player, slot, clickType, slotItem, cursorItem)
--     return true   -- let the click through; anything else (nil, false) cancels it
--   end
-- A slot without a callback cancels every click on it.
--
-- clickType values (Minecraft SlotActionType):
--   "pickup"      — left/right click
--   "quick_move"  — shift-click
--   "swap"        — number key swap
--   "clone"       — creative middle-click
--   "throw"       — drop key
--   "quick_craft" — drag-craft
--   "pickup_all"  — double-click gather
-- ==========================================================================

local chestgui = {}

local function toSlot(row, col)
    return (row - 1) * 9 + col
end

function chestgui.create(rows, title)
    local slots = rows * 9
    local inv = mc.createInventory(slots)
    local callbacks = {}
    local containers = {}
    local title = title or "Chest"

    local gui = {}
    gui.inventory = inv
    gui.title = title
    gui.rows = rows
    gui.slots = slots

    function gui:set(row, col, item, callback)
        local slot = toSlot(row, col)
        inv:setItem(slot, item)
        callbacks[slot] = callback
    end

    function gui:button(slot, item, callback)
        inv:setItem(slot, item)
        callbacks[slot] = callback
    end

    function gui:decorate(row, col, item)
        local slot = toSlot(row, col)
        inv:setItem(slot, item)
        callbacks[slot] = nil
    end

    function gui:fill(item)
        inv:fill(item)
    end

    function gui:clear()
        inv:clear()
    end

    function gui:open(player)
        local container = inv:open(player, title)
        if not container then return nil end
        containers[player.uuid] = container
        container:onClick(function(p, slot, clickType, slotItem, cursorItem, inGui)
            if inGui then
                -- a slot without a callback cancels the click; a button must return true to let it through
                local cb = callbacks[slot]
                return cb ~= nil and cb(p, slot, clickType, slotItem, cursorItem) == true
            end
            -- the player's own inventory or outside the window: allowed, except moves that reach into the GUI
            return clickType ~= "quick_move" and clickType ~= "pickup_all"
        end)
        return container
    end

    function gui:close(player)
        local c = containers[player.uuid]
        if c then
            c:close()
            containers[player.uuid] = nil
        end
    end

    function gui:setTitle(t)
        title = t
        gui.title = t
    end

    return gui
end

return chestgui
