---@meta

-- 3D vector. PxIgnis injects a `vec(x, y, z)` factory as a global.
-- Vectors are also represented as plain `{x, y, z}` tables in many APIs.

---@class Vec3Like
---@field x number
---@field y number
---@field z number

---@class Vec : Vec3Like
local Vec = {}

---Component-wise addition.
---@operator add(Vec|Vec3Like|number): Vec
---Component-wise subtraction.
---@operator sub(Vec|Vec3Like|number): Vec
---Component-wise (or scalar) multiplication.
---@operator mul(Vec|Vec3Like|number): Vec
---Scalar division (divisor must be a number).
---@operator div(number): Vec
---Negation.
---@operator unm: Vec
---Component-wise equality.
---@operator eq(Vec|Vec3Like): boolean
---Renders as `"(x, y, z)"`.
---@operator tostring: string

---Returns sqrt(x² + y² + z²).
---@return number
function Vec:length() end

---Returns x² + y² + z² (no sqrt).
---@return number
function Vec:lengthSq() end

---Returns the distance to another vector.
---@param other Vec3Like
---@return number
function Vec:distance(other) end

---Returns the squared distance (faster than distance for range checks).
---@param other Vec3Like
---@return number
function Vec:distanceSq(other) end

---Returns a normalized (unit) vector. Zero vector returns (0,0,0).
---@return Vec
function Vec:normalized() end

---Returns the dot product.
---@param other Vec3Like
---@return number
function Vec:dot(other) end

---Returns the cross product.
---@param other Vec3Like
---@return Vec
function Vec:cross(other) end

---Construct a 3D vector.
---@param x number
---@param y number
---@param z number
---@return Vec
function vec(x, y, z) end
