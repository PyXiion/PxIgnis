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

---Concise command registration. The template is rendered with `args.ctx` set
---to the command context (so `{ctx.player.name}` works), and `args.x` etc.
---for each declared argument.
---@param syntax string       -- must contain at least one <name:type> argument
---@param template string     -- Format template
---@param range? number       -- broadcast range; nil for global
---@param overlay? boolean  -- true = action bar instead of chat
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

---@class FxNode

---@class FxCtx
---@field world World
---@field origin Vec
---@field forward Vec  -- horizontal unit vector
---@field right Vec    -- horizontal unit vector
---@field viewers? Player|Player[]
local FxCtx = {}

---@param particle Particle
---@param pos Vec|Vec3Like
---@param opts? EmitOpts
function FxCtx:emit(particle, pos, opts) end

---@param particle Particle
---@param positions (Vec|Vec3Like)[]
---@param opts? EmitOpts
function FxCtx:emitMany(particle, positions, opts) end

---@class FxShapeOpts
---@field particle Particle
---@field duration? integer
---@field emit? EmitOpts
---@field radius? number
---@field points? integer
---@field spin? number

---@class FxHandle
---@field done boolean
local FxHandle = {}

---Ends the effect. Returns false if it had already finished.
---@return boolean
function FxHandle:stop() end

---Suspends the calling coroutine until the effect finishes or is stopped.
function FxHandle:wait() end

---@class FxPlayOpts
---@field at Vec|Entity|Player
---@field world? World       -- required with a Vec `at`
---@field follow? boolean    -- entity `at` only
---@field viewers? Player|Player[]

---@class FxLib
local Fx = {}

---@param list FxNode[]
---@return FxNode
function Fx.seq(list) end

---@param list FxNode[]
---@return FxNode
function Fx.par(list) end

---@param ticks integer  -- >= 1
---@return FxNode
function Fx.wait(ticks) end

---@param n integer  -- >= 1
---@param node FxNode
---@return FxNode
function Fx.times(n, node) end

---@param node FxNode
---@return FxNode
function Fx.loop(node) end

---@param opts { offset?: Vec, viewers?: Player|Player[] }
---@param node FxNode
---@return FxNode
function Fx.with(opts, node) end

---`factory()` runs once per start of the effect and returns `step(ctx)`, which runs once per tick.
---Return `true` from `step` to finish; otherwise it runs until stopped. Neither may yield.
---@param factory fun(): fun(ctx: FxCtx): boolean|nil
---@return FxNode
function Fx.effect(factory) end

---`fn(ctx, k)` runs once per tick with k = 0, 1, 2, ... for the current run. Ends after `ticks` ticks,
---or runs until stopped when `ticks` is nil. Must not yield.
---@param fn fun(ctx: FxCtx, k: integer)
---@param ticks? integer
---@return FxNode
function Fx.ticking(fn, ticks) end

---@param opts FxShapeOpts
---@return FxNode
function Fx.circle(opts) end

---@param opts FxShapeOpts
---@return FxNode
function Fx.sphere(opts) end

---Sphere growing from 0 to `radius` over `duration` ticks.
---@param opts FxShapeOpts
---@return FxNode
function Fx.burst(opts) end

---@param opts { particle: Particle, to: Vec|Entity, step?: number, duration?: integer, emit?: EmitOpts }
---@return FxNode
function Fx.line(opts) end

---@param opts FxShapeOpts|{ height?: number, strands?: integer, turns?: number }
---@return FxNode
function Fx.helix(opts) end

---@param node FxNode
---@param opts FxPlayOpts
---@return FxHandle
function Fx.play(node, opts) end
