---
title: Vector
description: 3D vector arithmetic with vec(x, y, z).
---

`vec` is a global constructor for 3D vectors. Creates a `{x, y, z}` table.
All methods that accept vectors will accept `{x, y, z}`, `{x=x, y=y, z=z}` and `vec(x, y, z)`

```lua
local v = vec(1, 2, 3)
```

Metatable name: `"vec"`

## Properties

Three read/write numeric properties: `v.x`, `v.y`, `v.z`.

## Methods

### `vec:length()`

Returns `sqrt(x² + y² + z²)` as a number.

```lua
local len = vec(3, 4, 0):length() -- 5.0
```

## Operators

| Operator   | Example            | Behaviour                       |
|------------|--------------------|---------------------------------|
| `+`        | `v1 + v2`          | Component-wise addition         |
| `-`        | `v1 - v2`          | Component-wise subtraction      |
| `*`        | `v1 * v2`          | Component-wise multiplication   |
| `*`        | `v * n` or `n * v` | Scalar multiplication           |
| `/`        | `v / n`            | Scalar division                 |
| `-`        | `-v`               | Negation                        |
| `==`       | `v1 == v2`         | Equality (all components equal) |
| `tostring` | `tostring(v)`      | Returns `"(x, y, z)"`           |

```lua
local a = vec(1, 2, 3)
local b = vec(4, 5, 6)

local sum     = a + b         -- vec(5, 7, 9)
local diff    = a - b         -- vec(-3, -3, -3)
local compMul = a * b         -- vec(4, 10, 18)
local scalar  = a * 10        -- vec(10, 20, 30)
local div     = a / 2         -- vec(0.5, 1, 1.5)
local neg     = -a            -- vec(-1, -2, -3)
print(tostring(a))            -- "(1, 2, 3)"
```