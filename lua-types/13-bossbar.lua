---@meta

-- Boss bar wrapper. Metatable name: `"bossbar"`.

---@class BossBar
local BossBar = {}

---@type string
BossBar.title = nil

---0..1.
---@type number
BossBar.progress = nil

---@type BossBarColor
BossBar.color = nil

---@type BossBarStyle
BossBar.style = nil

---@type boolean
BossBar.visible = nil

---@param player Player
function BossBar:addPlayer(player) end

---@param player Player
function BossBar:removePlayer(player) end

function BossBar:destroy() end
