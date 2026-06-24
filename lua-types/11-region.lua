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
---`region:off` to unsubscribe).
---@param event RegionEventName
---@param callback fun(...):boolean|nil  -- return false to cancel (cancellable events only)
---@param opts? { throttle?: integer }
---@return integer
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
