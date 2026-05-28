**PxRP** embeds a hot-swappable Lua runtime directly into the Fabric server lifecycle. No need to compile Java mods just to handle server-side logic, custom commands, or basic event manipulation. 
Write your logic in Lua, save the file, reload instantly, and see the changes live.

---

Right now, I am using PxRP to develop a complex, fully Vanilla custom Roguelike server. 
If an action felt clunky or required too much boilerplate during my own design phases, I changed the core engine to fix it. 
The result is a development loop explicitly streamlined for rapid, practical deployment.

---

## Key Features

* **Simple command registration:** They hook straight into Minecraft's native Brigadier system. You get tab completion, type validation (`block_pos`, `player`, `int`, etc.) with a simple API.
* **Fast Hot-Reloading:** Running `/pxrp reload` completely reloads the Lua state in memory within milliseconds. Registered commands are hot-patched into the live dispatcher without restarting the server.
* **Simple API:** 

---

## Some Snippets

### 1. Commands

```lua
-- Simple registration
register("pay <amount:int> <target:player>", function(ctx, amount, target)
    local bal = ctx.player.data.balance or 100
    if bal < amount then
        mc.broadcast(ctx.player.name .. " has insufficient funds.", true)
        return
    end
    ctx.player.data.balance = bal - amount
    target.data.balance = (target.data.balance or 0) + amount
    mc.broadcast(target.name .. " transferred " .. amount .. " coins to " .. target.name)
end, "pxrp.economy")

```

### 2. Events & cancellation

```lua
mc.on("player_block_break", function(player, pos, blockId)
    if blockId ~= "minecraft:white_wool" then
        return false -- Cancels the block break if it's not a wool block
    end
end)

```

### 3. Structure Placement

```lua
register("paste <id:text>", function(ctx, id)
    local s = mc.loadStructure(id) or mc.loadStructureFile(id .. ".nbt")
    if not s then return mc.broadcast("Structure not found!", true) end
    
    s:place(ctx.player.world, ctx.player.pos, {
        rotation = "CLOCKWISE_90",
        mirror = "LEFT_RIGHT",
        
        -- Iterate structure entities
        on_entity = function(entity, pos)
            entity.customName = "Summoned " .. entity.type
            entity.health = 50
        end
    })
end)

```

## Installation & Requirements

1. Install PxRP on your Fabric server.
2. The first boot generates a `config/pxrp/demo.lua` configuration file containing basic usage examples.
3. Edit your scripts and use `/pxrp reload` to apply changes instantly.

* **Minecraft:** `1.21.x`
* **Fabric Loader:** `≥0.19.2`
* **Fabric API:** `≥0.141.4`
* **Fabric Language Kotlin `≥1.10.8`**

---

## License

GNU Lesser General Public License v3.0