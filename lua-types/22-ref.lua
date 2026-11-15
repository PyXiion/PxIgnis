---@meta

-- `require "core:ref"`: local reactive values. See /libraries/ref.

---@class Ref<T>
local Ref = {}

---The current value; counts as a dependency of the running effect.
---@generic T
---@param self Ref<T>
---@return T
function Ref:get() end

---The current value without dependency tracking.
---@generic T
---@param self Ref<T>
---@return T
function Ref:peek() end

---Replaces the value. Watchers run only if it is not rawequal to the old one. Errors on a computed ref.
---@generic T
---@param self Ref<T>
---@param v T
function Ref:set(v) end

---Sets the value to `fn(old)`.
---@generic T
---@param self Ref<T>
---@param fn fun(old: T): T
function Ref:update(fn) end

---Runs `fn(new, old)` after each change (not on registration). Returns a disconnect function.
---@generic T
---@param self Ref<T>
---@param fn fun(new: T, old: T)
---@return fun() disconnect
function Ref:watch(fn) end

---Stops a computed ref from recomputing.
---@param self Ref
function Ref:dispose() end

---@class RefLib
local RefLib = {}

---@generic T
---@param initial T
---@return Ref<T>
function RefLib.new(initial) end

---@param x any
---@return boolean
function RefLib.isRef(x) end

---Runs `fn` now and again when any ref it read with `get()` changes. Returns `dispose`.
---@param fn fun()
---@return fun() dispose
function RefLib.effect(fn) end

---A read-only ref holding `fn()`, kept up to date automatically.
---@generic T
---@param fn fun(): T
---@return Ref<T>
function RefLib.computed(fn) end

---Records `r` as a dependency of the running effect. Call from the read method of a custom reactive container
---(it must provide `:watch(fn)` returning a disconnect function).
---@param r table
function RefLib.track(r) end
