---@meta

-- Top-level `mc` table. All entry points live here.

---@class mcApi
mc = {}

-- === Functions =============================================================

---@param text string
---@param overlay? integer  -- if set, send as overlay (action bar) for N ticks
function mc.broadcast(text, overlay) end

---@return number  -- Unix seconds
function mc.time() end

---Schedules a one-shot callback after `delay` ticks.
---@param delay integer
---@param callback fun()
---@return integer  -- task id
function mc.schedule(delay, callback) end

---Schedules a repeating callback.
---@param delay integer    -- initial delay
---@param interval integer -- tick interval
---@param callback fun()
---@return integer  -- task id
function mc.scheduleRepeating(delay, interval, callback) end

---@param id integer
---@return boolean  -- true if the task was found and removed
function mc.cancelTask(id) end

---@param name string  -- e.g. "minecraft.overworld", "minecraft.the_nether"
---@return World
function mc.world(name) end

---@param uuid string
---@return Entity|nil
function mc.getEntity(uuid) end

---@param id integer
---@return Region|nil
function mc.getRegion(id) end

---Returns all live holograms across all worlds.
---@return Hologram[]
function mc.holograms() end

---@param uuid string
---@return Hologram|nil
function mc.getHologram(uuid) end

---Loads a structure from the server's `structures/` folder.
---Throws if the structure is not found.
---@param id string
---@return Structure
function mc.loadStructure(id) end

---Loads a structure from a file path.
---@param path string
---@return Structure
function mc.loadStructureFile(path) end

---Dumps a Lua value to a string. Useful for debugging.
---@param value any
---@param maxDepth? integer
---@return string
function mc.dump(value, maxDepth) end

---Returns the metatable for a wrapper type. Useful for adding custom methods
---to all wrappers of that type.
---@param name '"entity"'|'"player"'|'"world"'|'"structure"'|'"item"'|'"vec"'|'"inventory"'|'"container"'|'"sidebar"'|'"mob"'|'"hologram"'|'"region"'|'"bossbar"'
---@return table
---@overload fun(name: '"entity"'): Entity
---@overload fun(name: '"player"'): Player
---@overload fun(name: '"world"'): World
---@overload fun(name: '"structure"'): Structure
---@overload fun(name: '"item"'): Item
---@overload fun(name: '"vec"'): Vec
---@overload fun(name: '"inventory"'): Inventory
---@overload fun(name: '"container"'): Container
---@overload fun(name: '"sidebar"'): Sidebar
---@overload fun(name: '"mob"'): Mob
---@overload fun(name: '"hologram"'): Hologram
---@overload fun(name: '"region"'): Region
---@overload fun(name: '"bossbar"'): BossBar
function mc.getMetatable(name)
end

---@param type '"item"'|'"inventory"'
---@param value Item|Inventory
---@return string  -- JSON
function mc.serialise(type, value) end

---@param type '"item"'|'"inventory"'
---@param json string
---@return Item|Inventory|nil
function mc.deserialise(type, json) end

---Creates a new inventory. Size must be 9..54 and a multiple of 9.
---@param size integer
---@return Inventory
function mc.createInventory(size) end

---Returns the current Yarn mapping namespace (e.g. "named", "intermediary",
---"mojang", or the mod's runtime namespace).
---@type string
mc.runtimeNamespace = nil

---Resolves a class name from the `named` namespace to the current runtime
---namespace. Useful for working with NBT or reflection.
---@param className string
---@return string
function mc.mapped(className) end

---Registers a custom mob behaviour handler. See /reference/mob-ai.
---@param id string
---@param fn fun(entity. Mob, ctx. table)
function mc.registerBehaviour(id, fn) end

---@param title string
---@param color? BossBarColor
---@param style? BossBarStyle
---@return BossBar
function mc.createBossBar(title, color, style) end

---Executes a Brigadier command and returns (ok, countOrMessage).
---@param command string
---@param opts? ExecuteOpts
---@return boolean, integer|string
function mc.execute(command, opts) end

---Creates an ItemStack. Two overloads.
---  createItem("minecraft.stone", 64)
---  createItem({ id = "minecraft.diamond_sword", count = 1, name = "Excalibur", lore = {...}, unbreakable = true, custom_model_data = 1 })
---@param idOrSpec string|table
---@param count? integer
---@return Item
function mc.createItem(idOrSpec, count) end

-- === Event methods =========================================================

---Subscribes a handler to an event. Returns a handler id (pass to `mc.off`).
---@param event PxIgnisEventName|string
---@param handler fun(...):boolean|nil  -- return false to cancel (cancellable events only)
---@return integer
function mc.on(event, handler) end

---@param id integer
---@return boolean
function mc.off(id) end

---Fires an event synchronously. Useful for cross-script communication.
---@param event string
function mc.emit(event, ...) end

-- === Properties ============================================================

---Live, per-tick list of online players.
---@type Player[]
mc.players = nil

---Number of currently connected players.
---@type integer
mc.onlineCount = nil

---Global persistent storage. Auto-saved to JSON.
---@type table<string, any>
mc.data = nil

-- === Async (coroutine only) ================================================

---Performs an HTTP request. **Only valid in a coroutine context** (e.g.
---inside `mc.schedule`); for event handlers, wrap with `mc.schedule(0, fn)`.
---@param request string|FetchRequest
---@return FetchResult
function mc.fetch(request) end

---Yields the current coroutine for the given number of server ticks.
---**Only valid in a coroutine context.**
---@param ticks integer
function mc.sleep(ticks) end
