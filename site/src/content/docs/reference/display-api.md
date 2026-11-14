---
title: Display
description: Virtual (packet-only) display entities - item, block, text and interaction hitboxes shown per player.
---

`world:display` creates a display entity that exists **only in packets**. The server has no entity for it: nothing is
saved into chunks, nothing is left behind after a crash, `/ignis reload` and shutdown remove every display from every
client, and each player can be shown or hidden it individually. Scripts re-create their decorations when they load.
The convenient layer is [`core:display`](/libraries/display). Existing `world:spawn("item_display")` and
[holograms](/reference/hologram-api) are untouched.

Metatable name: `"display"`. Listing: `mc.displays()`.

## `world:display(kind, pos, props?) -> Display`

`kind` is `"item"`, `"block"`, `"text"` or `"interaction"`. A bad property raises an error and creates nothing.

## Handle

| Member | Description |
|---|---|
| `h:set(props)` | Partial update. Changes made during a tick are diffed and sent as **one** entity-data packet per viewer at the end of the tick |
| `h:moveTo(pos, yaw?, pitch?)` | Teleports; the client smooths it over `teleport` ticks. Error while attached |
| `h:attach(entity, offset?)` | Virtual passenger: rides the entity on the client. `offset` is added to the transform translation. Resent when a player starts tracking the host |
| `h:detach()` | Stays where the host was |
| `h:show(player)` / `h:hide(player)` | Per-player visibility (see `viewers`) |
| `h:remove()` | Removes it from every viewer. Idempotent |
| `h:onClick(fn(player, hand))` | `interaction` only. `hand` is `"main"` or `"off"` |
| `h:onAttack(fn(player))` | `interaction` only |
| `h.valid`, `h.pos`, `h.kind`, `h.id`, `h.yaw`, `h.pitch` | Read-only |

Callback errors are reported like other script errors. A click on a virtual id never reaches vanilla handling.

## Viewers

| `viewers` | Meaning |
|---|---|
| `"auto"` (default) | Every 10 ticks the display is shown to players in the same world within `viewRange * 64` blocks and hidden from those who moved away. Also re-checked right after a player joins, respawns or changes world. With `attach`, the host's position is used |
| `"manual"` | Only `show`/`hide` |
| `{p1, p2}` | Fixed list: manual, with exactly these players shown |

In `"auto"` mode `hide` lasts until the next re-check; use `"manual"` to keep a player excluded.

Viewers are tracked by player UUID, not by object: a respawn replaces the player object while the client keeps
whatever it was sent. On respawn or world change the client gets a remove for the display and the next check decides
whether to show it again. `remove()`, `hide` and a reload reach every connected client that was sent the display, even
one that is now far away or in another world.

## Properties

One flat table, the same for `world:display` and `h:set`. A property of another kind is an error.

| Key | Type | Notes |
|---|---|---|
| `transform` | `{t = {x,y,z}, l = quat, s = number or {x,y,z}, r = quat}` or 16 numbers | Translation, left rotation, scale, right rotation. A matrix is row-major with translation in elements 4, 8, 12. A quat is `{x=,y=,z=,w=}` or `{x,y,z,w}` |
| `interp` | ticks | Time the client takes to reach a changed transform (and opacity, background, shadow). Default **0**. Every transform change restarts the interpolation |
| `teleport` | 0..59 | Smoothing of `moveTo`. Default 0 |
| `billboard` | `fixed`, `vertical`, `horizontal`, `center` | Default `fixed` |
| `brightness` | number 0..15, `{block, sky}` or `false` | `false` (the default) uses world light |
| `glow` | `false`, `true`, `0xRRGGBB` | Outline and its colour |
| `viewRange` | number | Default 1.0 (x 64 blocks) |
| `shadow` | number or `{radius, strength}` | Default none |
| `size` | `{w, h}` | Culling box. Default 0 (no culling) |
| `viewers` | see above | |
| `dieWithHost` | boolean | With `attach`: remove when the host entity is removed. Otherwise the display stays at the host's last position |
| `item` | id, item table (`item_model`, ...) or item | `item` kind |
| `mode` | `none`, `fixed`, `gui`, `ground`, `head`, `firstperson_lefthand`, ... | `item` kind |
| `block` | block state string, e.g. `minecraft:oak_stairs[facing=north]` | `block` kind |
| `text` | string (§ codes work) or a text component table | `text` kind |
| `background` | ARGB | `text` kind |
| `opacity` | 0..255 | `text` kind |
| `lineWidth`, `align` (`left`/`center`/`right`), `textShadow`, `seeThrough` | | `text` kind |
| `hitbox` | `{w, h, responsive}` | `interaction` kind. Default `{1, 1}`; `responsive` makes clicks swing the arm |

## Limits

- The host of `attach` must be tracked by the viewer for the display to follow it; hosts that are removed (death,
  disconnect, unload) leave the display behind unless `dieWithHost` is set.
- A virtual passenger is added to every vanilla passenger packet of the host, including for players that are not
  viewers; their client ignores the unknown id.
- `interaction` displays are not attachable in a useful way: a passenger's hitbox does not move with the host.
