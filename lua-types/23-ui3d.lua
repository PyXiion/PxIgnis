---@meta

-- `require "core:ui3d"`: in-world UI panels. See /libraries/ui3d.

---A prop value: plain, a `Ref` or a function (computed; re-run when the refs it reads change).
---@alias Ui3dProp<T> T|Ref<T>|fun():T

---@class Ui3dNode  -- descriptor returned by the constructors; instantiated by `panel`

---@class Ui3dStyle
---@field bg? integer          -- ARGB
---@field fg? integer          -- ARGB
---@field on? integer          -- toggle: track colour while on
---@field scale? number
---@field z? number            -- lift towards the viewer, blocks
---@field hover? Ui3dStyle
---@field press? Ui3dStyle
---@field disabled? Ui3dStyle

---@class Ui3dCommon
---@field id? string
---@field w? Ui3dProp<number>
---@field h? Ui3dProp<number>
---@field grow? Ui3dProp<number|boolean>
---@field style? string

---@class Ui3dContainerProps : Ui3dCommon
---@field gap? Ui3dProp<number>
---@field pad? Ui3dProp<number>
---@field align? Ui3dProp<"stretch"|"start"|"center"|"end">

---@class Ui3dTextProps : Ui3dCommon
---@field [1]? Ui3dProp<string|number>
---@field text? Ui3dProp<string|number>
---@field size? Ui3dProp<number>       -- line height in blocks (default 0.2)
---@field fg? Ui3dProp<integer>
---@field align? Ui3dProp<"left"|"center"|"right">

---@class Ui3dButtonProps : Ui3dTextProps
---@field bg? Ui3dProp<integer>
---@field disabled? Ui3dProp<boolean>
---@field onClick? fun(player: Player)

---@class Ui3dBarProps : Ui3dCommon
---@field [1]? Ui3dProp<number>
---@field value? Ui3dProp<number>      -- 0..1
---@field bg? Ui3dProp<integer>
---@field fg? Ui3dProp<integer>

---@class Ui3dToggleProps : Ui3dCommon
---@field [1]? Ref<boolean>|boolean
---@field value? Ref<boolean>|Ui3dProp<boolean>
---@field disabled? Ui3dProp<boolean>
---@field onChange? fun(value: boolean, player: Player)

---@class Ui3dIconProps : Ui3dCommon
---@field [1]? Ui3dProp<string>
---@field item? Ui3dProp<string>
---@field size? Ui3dProp<number>

---@class Ui3dPanelOpts
---@field at Vec|Vec3Like          -- panel centre
---@field facing? "north"|"south"|"east"|"west"   -- direction the front faces (default "south")
---@field width? number
---@field height? number
---@field world? World             -- default: the overworld
---@field theme? table<string, Ui3dStyle>
---@field reach? number            -- hover/click distance (default 8)
---@field range? number            -- per-player panels: players within this distance get a tree (default 24)

---@class Ui3dWidget
---@field id? string
---@field kind string
---@field p table                  -- current resolved props
---@field rect {x:number,y:number,w:number,h:number}  -- layout rectangle, blocks from the panel's top-left corner
local Ui3dWidget = {}

---Sets props to plain values and refreshes the displays.
---@param props table
function Ui3dWidget:set(props) end

---@class Ui3dPanel
local Ui3dPanel = {}

---The widget with `id`. Per-player panels need the player.
---@param id string
---@param player? Player
---@return Ui3dWidget|nil
function Ui3dPanel:get(id, player) end

---Removes every display and disconnects every binding.
function Ui3dPanel:remove() end

---@class Ui3dFrame
---@field o Vec3Like
---@field r Vec3Like
---@field u Vec3Like
---@field n Vec3Like
---@field w number
---@field h number

---@class Ui3dLib
local Ui3dLib = {}

---@param opts Ui3dPanelOpts
---@return fun(tree: Ui3dNode|Ui3dNode[]|fun(player: Player): Ui3dNode): Ui3dPanel
function Ui3dLib.panel(opts) end

---@param props Ui3dContainerProps|table
---@return Ui3dNode
function Ui3dLib.col(props) end
---@param props Ui3dContainerProps|table
---@return Ui3dNode
function Ui3dLib.row(props) end
---@param props Ui3dContainerProps|table
---@return Ui3dNode
function Ui3dLib.stack(props) end

---@param props Ui3dProp<string|number>|Ui3dTextProps
---@return Ui3dNode
function Ui3dLib.text(props) end
---@param props Ui3dButtonProps
---@return Ui3dNode
function Ui3dLib.button(props) end
---@param props string|Ui3dIconProps
---@return Ui3dNode
function Ui3dLib.icon(props) end
---@param props Ui3dBarProps
---@return Ui3dNode
function Ui3dLib.bar(props) end
---@param props Ui3dToggleProps
---@return Ui3dNode
function Ui3dLib.toggle(props) end
---Empty spacer.
---@param props Ui3dCommon
---@return Ui3dNode
function Ui3dLib.box(props) end

---@param cond Ui3dProp<any>
---@param node Ui3dNode
---@return Ui3dNode
function Ui3dLib.show(cond, node) end

---@generic T
---@param list T[]|Ref<T[]>|fun():T[]
---@param fn fun(item: T, index: integer): Ui3dNode
---@return Ui3dNode
function Ui3dLib.each(list, fn) end

---Default theme merged with `user`.
---@param user? table<string, Ui3dStyle>
---@return table<string, Ui3dStyle>
function Ui3dLib.theme(user) end

---@param theme table<string, Ui3dStyle>
---@param name string
---@param state? "hover"|"press"|"disabled"
---@return Ui3dStyle
function Ui3dLib.resolveStyle(theme, name, state) end

---Panel frame: centre `at`, facing, size in blocks.
---@param at Vec|Vec3Like
---@param facing "north"|"south"|"east"|"west"
---@param w number
---@param h number
---@return Ui3dFrame
function Ui3dLib.frame(at, facing, w, h) end

---Ray vs panel: layout coordinates (blocks from the top-left, y down) and ray distance, or nil.
---@param frame Ui3dFrame
---@param eye Vec|Vec3Like
---@param dir Vec|Vec3Like
---@param range? number
---@return number? x, number? y, number? t
function Ui3dLib.project(frame, eye, dir, range) end
