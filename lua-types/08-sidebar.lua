---@meta

-- Per-player sidebar. Metatable name: `"sidebar"`.
-- See also `Player.sidebar` for the convenience read/write property.

---@class Sidebar
local Sidebar = {}

---@type string
Sidebar.title = nil

---Lines as a 1-indexed array of strings.
---@type string[]
Sidebar.lines = nil

---@type boolean
Sidebar.visible = nil

---@type integer
Sidebar.lineCount = nil

---@param line integer  -- 1-indexed
---@param text string
function Sidebar:setLine(line, text) end

function Sidebar:show() end

function Sidebar:hide() end

function Sidebar:destroy() end
