---
title: Whitelist
description: A persistent player whitelist implemented entirely in Lua.
---

This example implements a simple whitelist without a separate database. It uses
[`mc.data`](/reference/storage) for persistence, a typed player command argument,
and the cancellable `player_join_init` event.

Save this as `config/ignis/whitelist.lua`:

```lua
local whitelist = mc.data.whitelist or {}
mc.data.whitelist = whitelist

mc.on("player_join_init", function(player)
    -- Keep operators able to join while setting up the whitelist.
    if player.isOp then
        return
    end

    if not whitelist[player.uuid] then
        return false
    end
end)

register("px whitelist add <target:player>", function(ctx, target)
    whitelist[target.uuid] = {
        name = target.name,
        addedAt = mc.time()
    }

    ctx.player:sendMessage("Added " .. target.name .. " to the whitelist.")
end, "px.whitelist")

local function removeEntry(ctx, uuid, name)
    if not whitelist[uuid] then
        ctx.player:sendMessage("Player not found in the whitelist: " .. name)
        return
    end

    whitelist[uuid] = nil
    ctx.player:sendMessage("Removed " .. name .. " from the whitelist.")
end

register("px whitelist remove <target:player>", function(ctx, target)
    removeEntry(ctx, target.uuid, target.name)
end, "px.whitelist")

register("px whitelist remove <target:word>", function(ctx, target)
    -- The word form also works for players who are offline.
    local query = target:lower()

    if whitelist[target] then
        removeEntry(ctx, target, target)
        return
    end

    for uuid, entry in pairs(whitelist) do
        if entry.name:lower() == query then
            removeEntry(ctx, uuid, entry.name)
            return
        end
    end

    removeEntry(ctx, target, target)
end, "px.whitelist")

register("px whitelist list", function(ctx)
    local count = 0

    for _, entry in pairs(whitelist) do
        count = count + 1
        ctx.player:sendMessage(entry.name)
    end

    ctx.player:sendMessage("Whitelist entries: " .. count)
end, "px.whitelist")
```

## Usage

The commands require the `px.whitelist` permission:

```text
/px whitelist add <online player>
/px whitelist remove <online player>
/px whitelist remove <offline name or UUID>
/px whitelist list
```

The `player` argument resolves an online player and supplies a stable UUID. The
UUID is used as the storage key, while the player name is kept for display.

The built-in `/whitelist` command is reserved by Minecraft, so this example
uses the `/px whitelist` command path instead.

## Notes

- The whitelist starts empty, so operators can join and add players.
- Non-whitelisted players are rejected during `player_join_init`, before the
  normal join event.
- Entries survive `/ignis reload` and server restarts through `mc.data`.
- The `player` form removes an online player by UUID.
- The `word` form removes an offline player by their saved name or UUID.

See also [registering commands](/reference/commands-api),
[events](/reference/events), and [persistent storage](/reference/storage).
