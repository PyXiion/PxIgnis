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
---@field defaultOverlay integer  -- ticks; default 140 (7s)
local Simple = {}

---Concise command registration. The template is rendered with `args.ctx` set
---to the command context (so `{ctx.player.name}` works), and `args.x` etc.
---for each declared argument.
---@param syntax string       -- must contain at least one <name:type> argument
---@param template string     -- Format template
---@param range? number       -- broadcast range; nil for global
---@param overlay? boolean|integer  -- true = default overlay; integer = ticks
function Simple.register(syntax, template, range, overlay) end

---`require "core:chestgui"`.
---@class ChestGui
local ChestGui = {}

---Click callback. Return `false` to cancel the click (items in an open GUI are locked
---either way, but a cancelled click is also not shown to the player as a pickup).
---@alias ChestGuiCallback fun(player: Player, slot: integer, clickType: string, slotItem: Item|nil, cursorItem: Item|nil): boolean|nil

---Creates a new chest GUI.
---@param rows integer  -- 1..6
---@param title? string
---@return ChestGuiInstance
function ChestGui.create(rows, title) end

---@class ChestGuiInstance
---@field inventory Inventory
---@field title string
---@field rows integer
---@field slots integer  -- rows * 9
local ChestGuiInstance = {}

---Puts an item at a grid position (1-based) with a click callback.
---@param row integer
---@param col integer
---@param item Item
---@param callback? ChestGuiCallback
function ChestGuiInstance:set(row, col, item, callback) end

---Puts an item at a raw slot (1-based) with a click callback.
---@param slot integer
---@param item Item
---@param callback? ChestGuiCallback
function ChestGuiInstance:button(slot, item, callback) end

---Puts an item without a callback; clicks on it are cancelled.
---@param row integer
---@param col integer
---@param item Item
function ChestGuiInstance:decorate(row, col, item) end

---Puts `item` into every slot (callbacks stay; call it before `set`/`button`).
---@param item? Item
function ChestGuiInstance:fill(item) end

function ChestGuiInstance:clear() end

---Opens the GUI for the player.
---@param player Player
---@return Container|nil
function ChestGuiInstance:open(player) end

---@param player Player
function ChestGuiInstance:close(player) end

---Changes the title used by the next `open`.
---@param title string
function ChestGuiInstance:setTitle(title) end

---`require "async"`. Only `sleep` and `fetch` are typed here; see
---https://ignis.pyxiion.ru/reference/async-api for tasks, promises and mutexes.
---@class AsyncLib
local Async = {}

---Yields the current coroutine for the given number of server ticks.
---Valid in command/event handlers, scheduler callbacks and async tasks.
---@param ticks integer
function Async.sleep(ticks) end

---Performs an HTTP request, yielding the current coroutine until it completes.
---@param request string|FetchRequest
---@return FetchResult
function Async.fetch(request) end
