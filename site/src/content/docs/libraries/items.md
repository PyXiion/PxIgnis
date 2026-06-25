---
title: items
description: Define custom items with scripted behaviors, using custom_model_data and base Minecraft items.
---

The `items` library lets you define custom item templates that combine a base Minecraft item with
`custom_model_data` (for resource-pack model overrides) and scripted callbacks that react to player
actions.

## Loading

```lua
local items = require "core:items"
```

## items.define(opts)

Creates a named item template and registers it for automatic event dispatch. Returns a template object.

### Options

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `string` | ✅ | Base Minecraft item ID (e.g. `"stick"`, `"minecraft:diamond_sword"`) |
| `modelData` | `number` | | CustomModelData value (for resource-pack model) |
| `key` | `string` | | Internal identifier; auto-generated if omitted |
| `name` | `string` | | Custom item name (`&` color codes) |
| `lore` | `string[]` | | Lore lines |
| `unbreakable` | `boolean` | | Unbreakable flag |

**Callback fields** — each is optional, triggered when the held/used item matches `id` + `modelData`:

| Callback | Trigger | Signature | Cancellable |
|----------|---------|-----------|:-----------:|
| `onUse` | Right-click with item | `fn(player, hand, item, template)` | ✅ |
| `onAttack` | Attack an entity while holding | `fn(player, target, item, template)` | ✅ |
| `onConsume` | Eat/drink the item | `fn(player, item, template)` | ✅ |
| `onPickup` | Player picks up the item | `fn(player, item, count, template)` | ✅ |
| `onInteractEntity` | Right-click an entity with item | `fn(player, entity, hand, item, template)` | ✅ |
| `onTick` | Each tick while held | `fn(player, item, hand, template)` — `hand` is `"main"\|"off"` | ❌ |
| `onInventoryTick` | Each tick while in any inventory slot | `fn(player, item, slot, template)` | ❌ |

Returning `false` from a cancellable callback cancels the underlying event.

### Template methods

| Method | Returns | Description |
|--------|---------|-------------|
| `template:make(count?)` | `ItemStack` | Creates an item stack with the template's properties |
| `template:matches(item)` | `boolean` | Checks if an `ItemStack` matches this template |
| `template:has(player)` | `boolean` | `true` if the player has any matching item in their inventory |

## Utility functions

### `items.is(item, template)`

Returns `true` if the item matches the template by `id` + `modelData`.

### `items.find(item)`

Returns the matching template for the given `ItemStack`, or `nil`.

### `items.has(player, template)`

Returns `true` if the player has any matching item in their inventory (same as `template:has(player)`).

## Full Example

```lua
local items = require "core:items"

local wand = items.define {
    id = "stick",
    modelData = 1001,
    name = "&5Magic Wand",
    lore = { "&7Right-click to cast a spell" },
    unbreakable = true,

    onUse = function(player, hand, item, template)
        player:sendMessage("&5*whoosh*")
        player.world:playSound("minecraft:entity.ender_dragon.growl", player.pos, 1.0, 1.0)
    end,

    onAttack = function(player, target, item, template)
        target:damage(5)
    end,

    onTick = function(player, item, hand, template)
        player:sendActionBar("&dWand ready (" .. hand .. ")")
    end,
}

local ring = items.define {
    id = "gold_nugget",
    modelData = 2001,
    name = "&eRing of Regeneration",

    onInventoryTick = function(player, item, slot, template)
        player:heal(0.5)
    end,
}

-- Give items to a player
register("wand") \{ ctx ->
    ctx.player:give(wand:make(1))
}

register("ring") \{ ctx ->
    ctx.player:give(ring:make(1))
}

-- Check if a player has the ring
register("hasring") \{ ctx ->
    if ring:has(ctx.player) then
        ctx.player:sendMessage("&aYou have the ring!")
    else
        ctx.player:sendMessage("&cYou don't have the ring.")
    end
}
```

## Notes

- Items are matched by `id` + `modelData`. If `modelData` is omitted, matching is by `id` only
  (use with caution — every item of that base type will trigger callbacks).
- IDs without a namespace automatically get `minecraft:` prepended.
- The library registers its event listeners once on `require`, so all templates share a single
  listener per event.
- Custom items require a [resource pack](https://minecraft.wiki/w/Resource_pack) with a model
  override for the base item and `custom_model_data` to be visually distinct from the base item.
