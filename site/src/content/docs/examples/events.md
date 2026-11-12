---
title: Events
description: Complete examples of event-driven scripts.
---

## 1. Welcome Message

```lua
mc.on("player.join", function(e)
    mc.broadcast(e.player.displayName .. " joined the server!")
end)
```

## 2. Bad Word Filter

```lua
local badWords = {"badword1", "badword2", "badword3"}

mc.on("player.chat", function(e)
    for _, word in ipairs(badWords) do
        if e.message:lower():find(word) then
            e.player:sendMessage("Watch your language!")
            e:cancel() -- the message is not sent
            return
        end
    end
end, { priority = "high" })
```

## 3. Block Break Protection

```lua
mc.on("block.break", function(e)
    if e.block == "minecraft:bedrock" then
        e.player:sendMessage("You cannot break bedrock!")
        e:cancel()
    end
end)
```

## 4. TNT Placement Prevention

```lua
mc.on("block.place", function(e)
    if e.block == "minecraft:tnt" then
        e.player:sendMessage("TNT is disabled on this server!")
        e:cancel()
    end
end)
```

## 5. Audit Log That Sees Cancelled Actions

Runs last and also receives events that other handlers cancelled:

```lua
mc.on("block.break", function(e)
    local status = e.cancelled and "denied" or "ok"
    print(("%s broke %s at %d %d %d: %s"):format(e.player.name, e.block, e.pos.x, e.pos.y, e.pos.z, status))
end, { priority = "lowest", receiveCancelled = true })
```
