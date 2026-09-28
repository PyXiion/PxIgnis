---@meta

-- Region wrapper. Metatable name: `"region"`.

---@class Region
local Region = {}

---Session-unique integer id. Stable until `/ignis reload` or server restart.
---@type integer
Region.id = nil

---World this region belongs to.
---@type World
Region.world = nil

---Players currently inside (live, recomputed on read).
---@type Player[]
Region.players = nil

---Entities currently inside (live, recomputed on read). Players appear here too.
---@type (Entity|Player)[]
Region.entities = nil

---Subscribes a callback to a region event. Returns a handler id (pass to
---`region:off` to unsubscribe). Event tables are described in 19-events.lua.
---@param event RegionEventName
---@param callback fun(e: RegionEvent)
---@param opts? EventOptions
---@return integer
---@overload fun(self: Region, event: '"enter"', callback: fun(e: RegionEntityEvent), opts?: EventOptions): integer
---@overload fun(self: Region, event: '"leave"', callback: fun(e: RegionEntityEvent), opts?: EventOptions): integer
---@overload fun(self: Region, event: '"move"', callback: fun(e: RegionMoveEvent), opts?: EventOptions): integer
---@overload fun(self: Region, event: '"death"', callback: fun(e: RegionDeathEvent), opts?: EventOptions): integer
function Region:on(event, callback, opts) end

---@param id integer
---@return boolean
function Region:off(id) end

function Region:destroy() end

---@param pos Vec|Vec3Like
---@return boolean
function Region:contains(pos) end

---@return RegionBoundsTable
function Region:getBounds() end

---@param posA Vec|Vec3Like
---@param posB Vec|Vec3Like
function Region:setBounds(posA, posB) end
