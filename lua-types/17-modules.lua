---@meta

-- Bundled Lua libraries, accessed via `require("core:...")`.

---@class FormatLib
local Format = {}

---Renders an f-string-like template. `{expr}` evaluates `expr` against the
---args table; `{name}` substitutes `args.name` directly.
---@param template string
---@param args? table
---@return string
function Format.format(template, args) end

---Renders and broadcasts the result as a chat message.
---@param template string
---@param args? table
function Format.broadcastFormat(template, args) end

---@class SimpleLib
local Simple = {}

---@field defaultOverlay integer  -- ticks; default 140 (7s)

---Concise command registration. The template is rendered with `args.ctx` set
---to the command context (so `{ctx.player.name}` works), and `args.x` etc.
---for each declared argument.
---@param syntax string       -- must contain at least one <name:type> argument
---@param template string     -- Format template
---@param range? number       -- broadcast range; nil for global
---@param overlay? boolean|integer  -- true = default overlay; integer = ticks
function Simple.register(syntax, template, range, overlay) end

---@class ChestGui
local ChestGui = {}

---Creates a new chest GUI. Returns an object with a grid API.
---@param rows integer  -- 1..6
---@param title? string
---@return ChestGuiInstance
function ChestGui.create(rows, title) end

---@class ChestGuiInstance
---@field rows integer
---@field cols integer  -- always 9
---@field onClick? fun(player: Player, slot: integer, clickType: string, slotItem: Item|nil, cursorItem: Item|nil):boolean|nil
local ChestGuiInstance = {}

---Opens the GUI for the given player.
---@param player Player
---@return Container
function ChestGuiInstance:open(player) end

---@param player Player
function ChestGuiInstance:close(player) end

---Sets the item in a slot.
---@param row integer
---@param col integer
---@param item? Item
function ChestGuiInstance:setItem(row, col, item) end

---@param row integer
---@param col integer
---@return Item|nil
function ChestGuiInstance:getItem(row, col) end

function ChestGuiInstance:fill(item) end

function ChestGuiInstance:clear() end
