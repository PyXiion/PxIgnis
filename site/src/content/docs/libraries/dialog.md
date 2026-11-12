---
title: dialog
description: Server dialogs (Minecraft 1.21.6+) - text, numbers, options and buttons with checked input and click handlers.
---

The `dialog` library shows the dialog screens of Minecraft 1.21.6+ and calls your function when a button is clicked.
It builds the vanilla dialog, routes the clicks, and checks what the client sends back before your handler sees it.

## Loading

```lua
local dialog = require "core:dialog"
```

## Example

```lua
register("shop", function(ctx)
    local player = ctx.player
    dialog.show(player, {
        title = "Shop",
        body = { "Pick an amount.", dialog.item(mc.createItem("minecraft:apple"), "Fresh apples") },
        inputs = {
            dialog.number("amount", "Amount", 1, 64, { step = 1, initial = 1 }),
            dialog.option("pay", "Pay with", { { "coins", "Coins" }, { "gems", "Gems" } }),
            dialog.bool("gift", "Wrap as a gift"),
        },
        buttons = {
            dialog.button("Buy", function(p, v)
                p:sendMessage("Bought " .. v.amount .. " for " .. v.pay)
            end),
            player.isOp and dialog.command("Reset shop", "shop reset"),
            dialog.link("Rules", "https://example.com/rules"),
        },
        onClose = function(p, reason) end,
    })
end)
```

## `dialog.show(player, spec)`

| Field          | Description                                                                            |
|----------------|----------------------------------------------------------------------------------------|
| `title`        | Required                                                                               |
| `externalTitle`| Name used where the dialog is listed (pause menu button)                               |
| `canEscape`    | Escape closes the dialog (default `true`)                                              |
| `columns`      | Button columns                                                                         |
| `body`         | List of strings and `dialog.item(...)`                                                 |
| `inputs`       | List of `dialog.text/number/option/bool(...)`                                          |
| `buttons`      | List of `dialog.button/command/link/copy(...)`; `nil` and `false` entries are skipped  |
| `onClose`      | `function(player, reason)`, see below                                                  |

A player has one dialog from this library at a time: showing another one replaces it. Dialogs never pause the game.
With no buttons the dialog has an OK button; with one button and `canEscape = false` it is a vanilla notice, otherwise
a multi-action dialog.

### Body

- a string is a plain text line;
- `dialog.item(stack, text?, { tooltip, decorations, width, height })` shows an item (an `Item` or an id string).

### Inputs

Every input has a `key` (a name, unique in the dialog) and a `label`; the values reach the handler under the key.

| Constructor                                                       | Value   | Options                                                      |
|-------------------------------------------------------------------|---------|--------------------------------------------------------------|
| `dialog.text(key, label, opts)`                                   | string  | `max` (default 32), `initial`, `lines`, `height`, `width`, `hideLabel` |
| `dialog.number(key, label, min, max, opts)`                       | number  | `step`, `initial`, `format` (a translation key), `width`     |
| `dialog.option(key, label, { {id, label}, ... }, opts)`           | string (the id) | `initial` (an id), `width`, `hideLabel`              |
| `dialog.bool(key, label, opts)`                                   | boolean | `initial`                                                    |

### Buttons

| Constructor                                  | Does                                                                                  |
|----------------------------------------------|---------------------------------------------------------------------------------------|
| `dialog.button(label, fn, opts)`             | Calls `fn(player, values)` on the server                                              |
| `dialog.command(label, cmd, opts)`           | Runs `cmd` as the player with their own permissions, no confirmation screen            |
| `dialog.link(label, url)`                    | Opens a URL on the client                                                             |
| `dialog.copy(label, text)`                   | Copies text on the client                                                             |

`opts` for `button` and `command`: `tooltip`, `width` and `after`:

- `"wait"` (default) — the dialog closes after the handler, unless the handler shows another dialog;
- `"close"` — the client closes it at once;
- `"keep"` — it stays open and can be clicked again.

Inside a handler show the next dialog with `dialog.show`; a dialog opened with `player:showDialog` there is closed
again. A dialog that is not `"keep"` takes one click: later clicks on it are ignored. A failing handler is reported
like an event handler error.

### Checked input

Before a handler runs the values are checked against the spec: text length, number range and step, option ids, and the
set of keys. A click that does not match is dropped, a warning goes to the log and to operators
(see [`mc.warn`](/reference/mc-api#mcwarncontext-text)), and the dialog ends with reason `"invalid"`. Clicks with a
stale or foreign token are dropped silently.

### `onClose(player, reason)`

Runs once when the dialog ends without a button handling it:

| reason       | when                                            |
|--------------|-------------------------------------------------|
| `"escape"`   | the player pressed Escape (or OK on an empty dialog) |
| `"replaced"` | another `dialog.show` for this player           |
| `"leave"`    | the player disconnected                         |
| `"reload"`   | scripts were reloaded                           |
| `"closed"`   | `dialog.close(player)`                          |
| `"invalid"`  | the client sent values that did not match       |

## `dialog.close(player)`

Closes the player's dialog from this library.

## Pause menu button

With `menu.enabled` set to `true` in `config/pxignis.json`, the server adds a dialog `pxignis:menu` to the pause screen
and to the quick actions menu (vanilla tags `pause_screen_additions` and `quick_actions`; other entries stay).

```json
{ "menu": { "enabled": false, "title": "Server menu", "button": "Open" } }
```

`title` is the pause-menu button text and the dialog title, `button` the label of its button. The file is created with
these defaults on first start and read when the server starts (change it, then restart). Clicking the button sends the custom click `pxignis:menu`:

```lua
mc.on("player.custom_click", function(e)
    if e.id == "pxignis:menu" then openMainMenu(e.player) end
end)
```

## Notes

- Needs a Minecraft 1.21.6+ client. Dialogs shown before joining (configuration phase) are not supported.
- Labels and text are plain strings; rich text components are not supported.
- For anything the library does not cover use the raw [`player:showDialog(spec)`](/reference/player-api#playershowdialogspec)
  with the vanilla dialog JSON.
