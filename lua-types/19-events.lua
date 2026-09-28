---@meta

-- =============================================================================
-- Events: `mc.on(name, function(e) ... end, opts)` and `region:on(...)`.
-- Every built-in event passes one table `e`. Cancellable events are stopped
-- with `e:cancel()`. See /reference/events for details.
-- =============================================================================

---Options for `mc.on` / `region:on`.
---@class EventOptions
---Higher runs first (default 0). Aliases: "lowest" = -200, "low" = -100,
---"normal" = 0, "high" = 100, "highest" = 200.
---@field priority? integer|'"lowest"'|'"low"'|'"normal"'|'"high"'|'"highest"'
---Also call this handler when an earlier handler cancelled the event (default false).
---@field receiveCancelled? boolean
---Minimum number of ticks between two calls of this handler (default 0).
---@field throttle? integer

---@class Event
---@field name string        -- the event name, e.g. "block.break"
---@field cancellable boolean
---@field cancelled boolean  -- set by e:cancel(); a handler with receiveCancelled may set it back to false
---@field reason? string     -- set by e:cancel(reason)
local Event = {}

---Cancels the event. Only cancellable events; only before the handler's first async call.
---@param reason? string  -- for "player.login": the kick message
function Event:cancel(reason) end

---@class PlayerEvent : Event
---@field player Player

---@class PlayerRespawnEvent : PlayerEvent
---@field alive boolean  -- true when returning from the End, false after death

---@class PlayerChatEvent : PlayerEvent
---@field message string

---@class PlayerMoveEvent : PlayerEvent
---@field from Vec
---@field to Vec

---@class PlayerUseItemEvent : PlayerEvent
---@field hand '"main"'|'"off"'
---@field item Item|nil
---@field itemId string

---@class PlayerAttackEvent : PlayerEvent
---@field target Entity

---@class PlayerInteractEvent : PlayerEvent
---@field target Entity
---@field hand '"main"'|'"off"'

---@class PlayerKillEvent : PlayerEvent
---@field target Entity
---@field source string  -- damage type, e.g. "player"

---@class PlayerItemEvent : PlayerEvent
---@field item Item

---@class PlayerItemCountEvent : PlayerItemEvent
---@field count integer

---@class BlockEvent : PlayerEvent
---@field pos Vec
---@field block string  -- block id, e.g. "minecraft:stone"

---@class EntityEvent : Event
---@field entity Entity
---@field player? Player  -- the same object as `entity` when it is a player

---@class EntityDamageEvent : EntityEvent
---@field source string        -- damage type, e.g. "fall", "player_attack"
---@field amount number
---@field attacker Entity|nil

---@class EntityDamagedEvent : EntityDamageEvent
---@field blocked boolean      -- blocked by a shield

---@class RegionEvent : Event
---@field region Region

---@class RegionEntityEvent : RegionEvent
---@field entity Entity
---@field player? Player

---@class RegionMoveEvent : RegionEntityEvent
---@field from Vec
---@field to Vec

---@class RegionDeathEvent : RegionEntityEvent
---@field source string
---@field amount number

---Subscribes a handler to an event. Returns a handler id (pass to `mc.off`).
---Names with a `:` are custom events (`mc.emit`); their handlers get the emitted arguments.
---@param event PxIgnisEventName|string
---@param handler fun(...)
---@param opts? EventOptions
---@return integer
---@overload fun(event: '"init"'|'"uninit"'|'"server_start"'|'"server_stop"'|'"tick"', handler: fun(e: Event), opts?: EventOptions): integer
---@overload fun(event: '"player.login"'|'"player.join"'|'"player.leave"', handler: fun(e: PlayerEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.respawn"', handler: fun(e: PlayerRespawnEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.chat"', handler: fun(e: PlayerChatEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.move"', handler: fun(e: PlayerMoveEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.use_item"', handler: fun(e: PlayerUseItemEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.attack"', handler: fun(e: PlayerAttackEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.interact"', handler: fun(e: PlayerInteractEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.kill"', handler: fun(e: PlayerKillEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.consume"', handler: fun(e: PlayerItemEvent), opts?: EventOptions): integer
---@overload fun(event: '"player.pickup"'|'"player.drop"', handler: fun(e: PlayerItemCountEvent), opts?: EventOptions): integer
---@overload fun(event: '"block.break"'|'"block.place"', handler: fun(e: BlockEvent), opts?: EventOptions): integer
---@overload fun(event: '"entity.spawn"'|'"entity.despawn"', handler: fun(e: EntityEvent), opts?: EventOptions): integer
---@overload fun(event: '"entity.hurt"'|'"entity.death"', handler: fun(e: EntityDamageEvent), opts?: EventOptions): integer
---@overload fun(event: '"entity.damaged"', handler: fun(e: EntityDamagedEvent), opts?: EventOptions): integer
---@overload fun(event: PxIgnisEventName|string, opts: EventOptions, handler: fun(e: Event)): integer  -- Nova trailing block order
function mc.on(event, handler, opts) end

---Removes a handler registered with `mc.on`.
---@param id integer
---@return boolean  -- true if the handler was found and removed
function mc.off(id) end

---Fires a custom event (its name must not be a built-in one). Handlers run before this returns.
---@param event string  -- e.g. "mymod:boss_killed"
---@param ... any       -- passed to the handlers as they are
---@return boolean      -- false if a handler returned false (cancelled)
function mc.emit(event, ...) end
