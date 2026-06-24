---@meta

-- Hologram (text display) wrapper. Metatable name: `"hologram"`. Inherits from Entity.

---@class Hologram : Entity
local Hologram = {}

---@type string
Hologram.text = nil

---Multi-line text. Setting accepts a 1-indexed sequence of strings.
---@type string[]
Hologram.lines = nil

---@type HologramAlignment
Hologram.alignment = nil

---@type HologramBillboard
Hologram.billboard = nil

---@type integer
Hologram.lineWidth = nil

---ARGB background color, 0 for transparent.
---@type integer
Hologram.background = nil

---0..255.
---@type integer
Hologram.opacity = nil

---@type boolean
Hologram.shadow = nil

---@type boolean
Hologram.seeThrough = nil

---@type boolean
Hologram.glowing = nil

---@param index integer  -- 1-indexed
---@param text string
function Hologram:setLine(index, text) end

function Hologram:destroy() end
