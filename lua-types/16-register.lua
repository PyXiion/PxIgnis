---@meta

-- `register(syntax, handler, permission?)` global function.

---Registers a Brigadier command.
---
--- Syntax grammar:
---   name                       literal subcommand
---   <name:type>                required argument
---   [name:type]                optional argument
---   <name:choice=a,b,c>        constrained choice
---
--- Built-in argument types:
---   text        -- chat component (string)
---   word        -- single word (string)
---   player      -- online player name (Player wrapper)
---   target      -- alias of player
---   entity      -- entity selector (Entity wrapper)
---   int         -- integer
---   double      -- double
---   float       -- float
---   bool        -- boolean
---   block_pos   -- block position ({x,y,z} table)
---   choice=...  -- constrained choice
---
---@param syntax string
---@param handler fun(ctx: CommandContext, ...)
---@param permission? string
function register(syntax, handler, permission) end

---@class CommandContext
---@field player Player
local CommandContext = {}
