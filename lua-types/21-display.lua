---@meta

-- Virtual (packet-only) display entities: `world:display` and the `core:display` library.
-- Metatable name: `"display"`.

---@alias DisplayKind "item"|"block"|"text"|"interaction"
---@alias DisplayBillboard "fixed"|"vertical"|"horizontal"|"center"
---@alias DisplayAlign "left"|"center"|"right"

---Rotation as `{x = , y = , z = , w = }` (or a plain `{x, y, z, w}` array).
---@class Quat
---@field x number
---@field y number
---@field z number
---@field w number

---Translation, left rotation, scale, right rotation (applied in this order: translate * left * scale * right).
---@class DisplayTransform
---@field t? Vec|Vec3Like
---@field l? Quat
---@field s? number|Vec3Like
---@field r? Quat

---Players selection: automatic (everyone within `viewRange * 64` blocks), manual (`show`/`hide`) or a fixed list.
---@alias DisplayViewers "auto"|"manual"|Player[]

---Every property of `world:display` / `Display:set`. Kind-specific ones are rejected on other kinds.
---@class DisplayProps
---@field transform? DisplayTransform|number[]       -- or 16 numbers, row-major (translation in elements 4, 8, 12)
---@field interp? integer                  -- ticks the client takes to move to a new transform / opacity / ... (default 0)
---@field teleport? integer                -- 0..59 ticks the client smooths `moveTo` over (default 0)
---@field billboard? DisplayBillboard
---@field brightness? number|{[1]:number,[2]:number}|{block:number,sky:number}|false  -- 0..15 or {block, sky}; false = world light
---@field glow? boolean|integer            -- true, false or a 0xRRGGBB outline colour
---@field viewRange? number                -- default 1.0 (times 64 blocks)
---@field shadow? number|{radius:number,strength:number}
---@field size? {w:number,h:number}        -- culling box
---@field item? string|table|ItemStack     -- item kind: id, item table (`item_model`, ...) or an item
---@field mode? string                     -- item kind: none, fixed, gui, ground, head, ...
---@field block? string                    -- block kind: block state string, e.g. "minecraft:oak_stairs[facing=north]"
---@field text? string|table               -- text kind: string (§ codes work) or a text component table
---@field background? integer              -- text kind: ARGB
---@field opacity? integer                 -- text kind: 0..255
---@field lineWidth? integer
---@field align? DisplayAlign
---@field textShadow? boolean
---@field seeThrough? boolean
---@field hitbox? {w:number,h:number,responsive:boolean}  -- interaction kind (default {1, 1})
---@field viewers? DisplayViewers          -- default "auto"
---@field dieWithHost? boolean             -- with `attach`: remove the display when the host entity is removed

---@class Display
---@field valid boolean
---@field kind DisplayKind
---@field id integer
---@field pos Vec
---@field yaw number
---@field pitch number
local Display = {}

---Partial update; everything changed this tick goes out as one packet.
---@param props DisplayProps
function Display:set(props) end

---Moves the display; the client smooths it over `teleport` ticks. Not allowed while attached.
---@param pos Vec|Vec3Like
---@param yaw? number
---@param pitch? number
function Display:moveTo(pos, yaw, pitch) end

---Rides the entity on the client (a virtual passenger). `offset` is added to the transform translation.
---@param entity Entity
---@param offset? Vec|Vec3Like
function Display:attach(entity, offset) end

function Display:detach() end

---@param player Player
function Display:show(player) end

---@param player Player
function Display:hide(player) end

function Display:remove() end

---Interaction kind only.
---@param fn fun(player: Player, hand: "main"|"off")|nil
function Display:onClick(fn) end

---Interaction kind only.
---@param fn fun(player: Player)|nil
function Display:onAttack(fn) end

---@class World
local World = {}

---Spawns a virtual display: it exists only on clients and is removed on `/ignis reload` and shutdown.
---@param kind DisplayKind
---@param pos Vec|Vec3Like
---@param props? DisplayProps
---@return Display
function World:display(kind, pos, props) end

-- `require "core:display"`

---Handle returned by the `core:display` helpers: forwards the `Display` methods and adds sugar.
---@class DisplayHandle
---@field h Display            -- the core handle
---@field valid boolean
---@field kind DisplayKind
---@field id integer
---@field pos Vec
local DisplayHandle = {}

---@param props DisplayProps
function DisplayHandle:set(props) end

---`set` with `interp = ticks`.
---@param props DisplayProps
---@param ticks integer
function DisplayHandle:animate(props, ticks) end

function DisplayHandle:moveTo(pos, yaw, pitch) end
function DisplayHandle:attach(entity, offset) end
function DisplayHandle:detach() end
function DisplayHandle:show(player) end
function DisplayHandle:hide(player) end
function DisplayHandle:remove() end
function DisplayHandle:onClick(fn) end
function DisplayHandle:onAttack(fn) end

---Endless rotation about the model's own axis in steps shorter than 180 degrees.
---@param axis Vec3Like
---@param period integer  -- ticks per full turn (>= 2)
---@param opts? {step?:integer, dir?:number, phase?:number}
---@return DisplayHandle
function DisplayHandle:spin(axis, period, opts) end

function DisplayHandle:stopSpin() end

---Smooth move: `moveTo` with `teleport = min(ticks, 59)`.
---@param pos Vec|Vec3Like
---@param ticks integer
function DisplayHandle:flyTo(pos, ticks) end

---Text goes transparent, other kinds shrink to nothing.
---@param ticks integer
---@param thenRemove? boolean
function DisplayHandle:fade(ticks, thenRemove) end

---Removes the display after `ticks`.
---@param ticks integer
---@return DisplayHandle
function DisplayHandle:life(ticks) end

---@class DisplayHelperOpts : DisplayProps
---@field scale? number|Vec3Like
---@field rotation? Quat
---@field offset? Vec|Vec3Like

---@class DisplayNodeSpec : DisplayProps
---@field kind "item"|"block"|"text"
---@field parent? string

---@class DisplayRig
---@field valid boolean
local DisplayRig = {}

---Sets the parent transform; every node's matrix is recomputed.
---@param t DisplayTransform|number[]
---@param ticks? integer
function DisplayRig:transform(t, ticks) end

---@param name string
---@return DisplayHandle
function DisplayRig:node(name) end

function DisplayRig:attach(entity, offset) end
function DisplayRig:detach() end
function DisplayRig:show(player) end
function DisplayRig:hide(player) end
function DisplayRig:moveTo(pos, yaw, pitch) end
---Applies non-transform properties to every node.
---@param props DisplayProps
function DisplayRig:set(props) end
function DisplayRig:remove() end
---@param ticks integer
---@return DisplayRig
function DisplayRig:life(ticks) end

---@class DisplayKeyframe
---@field t integer
---@field pos? Vec3Like
---@field rot? number[]           -- {rx, ry, rz} radians
---@field scale? number|Vec3Like
---@field ease? "linear"|"in"|"out"|"inOut"

---@class DisplayTimelineDef
---@field loop? boolean
---@field step? integer
---@field length? integer
---@field tracks table<string, DisplayKeyframe[]>

---@class DisplayTimeline
---@field loop boolean
---@field step integer
---@field length integer
local DisplayTimeline = {}

---@param track string
---@param time number
---@return DisplayTransform
function DisplayTimeline:sample(track, time) end

---@class DisplayPlay
---@field done boolean
local DisplayPlay = {}
function DisplayPlay:stop() end

---@class DisplayBar
---@field frac number
---@field rig DisplayRig
local DisplayBar = {}

---@param frac number  -- 0..1
---@param ticks? integer
function DisplayBar:set(frac, ticks) end
function DisplayBar:attach(entity, offset) end
function DisplayBar:detach() end
function DisplayBar:show(player) end
function DisplayBar:hide(player) end
function DisplayBar:moveTo(pos, yaw, pitch) end
function DisplayBar:remove() end

---@class DisplayLib
local DisplayLib = {}

---@class DisplayQuatLib
DisplayLib.quat = {}
---@return Quat
function DisplayLib.quat.identity() end
---@param axis Vec3Like
---@param rad number
---@return Quat
function DisplayLib.quat.axis(axis, rad) end
---@param yaw? number
---@param pitch? number
---@param roll? number
---@return Quat
function DisplayLib.quat.euler(yaw, pitch, roll) end
---`mul(a, b)` applies b first.
---@param a Quat
---@param b Quat
---@return Quat
function DisplayLib.quat.mul(a, b) end
---@return Quat
function DisplayLib.quat.slerp(a, b, t) end

---@class DisplayMatLib
DisplayLib.mat = {}
---@return number[]
function DisplayLib.mat.identity() end
---translate(t) * rotate(l) * scale(s) * rotate(r); all optional.
---@return number[]
function DisplayLib.mat.compose(t, l, s, r) end
---@return number[]
function DisplayLib.mat.mul(a, b) end
---@return {x:number,y:number,z:number}
function DisplayLib.mat.apply(m, v) end
---A `transform` value (table or matrix) as a matrix.
---@return number[]
function DisplayLib.mat.from(tr) end

---@param world World
---@param pos Vec|Vec3Like
---@param item string|table|ItemStack
---@param opts? DisplayHelperOpts
---@return DisplayHandle
function DisplayLib.item(world, pos, item, opts) end

---@param world World
---@param pos Vec|Vec3Like
---@param block string
---@param opts? DisplayHelperOpts
---@return DisplayHandle
function DisplayLib.block(world, pos, block, opts) end

---@param world World
---@param pos Vec|Vec3Like
---@param text string|table
---@param opts? DisplayHelperOpts
---@return DisplayHandle
function DisplayLib.text(world, pos, text, opts) end

---Coloured rectangle of `w` x `h` blocks (a text display with a background).
---@param world World
---@param pos Vec|Vec3Like
---@param argb integer
---@param w number
---@param h number
---@param opts? DisplayHelperOpts
---@return DisplayHandle
function DisplayLib.quad(world, pos, argb, w, h, opts) end

---Wraps a core `Display` to get the sugar methods.
---@param core Display
---@return DisplayHandle
function DisplayLib.wrap(core) end

---@param world World
---@param pos Vec|Vec3Like
---@param spec table<string, DisplayNodeSpec>
---@return DisplayRig
function DisplayLib.group(world, pos, spec) end

---@param def DisplayTimelineDef
---@return DisplayTimeline
function DisplayLib.timeline(def) end

---@param target DisplayRig|DisplayHandle
---@param anim DisplayTimeline
---@param opts? {step?:integer}
---@return DisplayPlay
function DisplayLib.play(target, anim, opts) end

---@param world World
---@param pos Vec|Vec3Like
---@param opts? {w?:number,h?:number,fg?:integer,bg?:integer,frac?:number}|DisplayProps
---@return DisplayBar
function DisplayLib.bar(world, pos, opts) end
