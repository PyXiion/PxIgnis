---
title: Libraries
description: PxIgnis ships with bundled Lua libraries that can be loaded via require().
---

PxIgnis includes several Lua libraries in. Load them with `require()`:

```lua
local format = require "core:format"
local simple = require "core:simple"
local chestgui = require "core:chestgui"
local items = require "core:items"
```

## Available Libraries

| Library                         | Description                                           |
|---------------------------------|-------------------------------------------------------|
| [format](/libraries/format)     | F-string-like text templating                         |
| [simple](/libraries/simple)     | Concise command registration with built-in formatting |
| [chestgui](/libraries/chestgui) | Chest-based GUI creation                              |
| [items](/libraries/items)       | Custom item templates with scripted callbacks         |

## Loading from Subdirectories

Files in subdirectories of `config/ignis/` can be loaded using dot notation:

```lua
local utils = require "libs.utils"
```

This resolves to `config/ignis/libs/utils.lua`.