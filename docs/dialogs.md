# Dialogs

Minecraft 1.21.6 added server dialogs: screens with text, inputs and buttons. PxIgnis exposes them in two layers.

## Raw API and Lua library

The Kotlin side stays thin and close to vanilla. `player:showDialog(spec)` takes a table in the vanilla dialog JSON shape
and decodes it with the game's own `Dialog.DIRECT_CODEC`, so every dialog type and field the game supports works, and a
bad spec fails with the codec's message. `player:closeDialog()` and the `player.custom_click` event complete it. Nothing
here knows about tokens, handlers or input checks.

The convenient layer is Lua (`core:dialog`). It can change without a Kotlin release, it reads like the rest of the
scripting API (constructors and closures), and it needs no per-version code: the vanilla dialog classes are identical on
1.21.11 and 26.3, and only the JSON shape crosses the boundary.

## Click routing

A button that must reach a script uses the `dynamic/custom` action. Its id is the only routing data we control: the
client appends the input values to the same compound as the action's `additions`, so a payload cannot carry a trusted
token. The library puts the routing into the id, `pxignis:d/<token>/<n>` (valid identifier characters):

- `<token>` is random and belongs to one `dialog.show`; a player has one active dialog. A click whose token is not the
  active one (stale dialog, double click, another script's id) is dropped.
- `<n>` is the button index, or `x` for the escape/OK action.

Custom click packets are sent by the client and can be forged at will, so the library also checks the values against the
spec before the handler runs (text length, number range and step, option ids, boolean coercion from the byte tag, no
unknown keys). A click that does not match is dropped and reported with `mc.warn`.

The packet carries no player, and vanilla's `MinecraftServer.handleCustomClickAction` receives none, so the event is
raised from a mixin on the game-phase packet listener (after the packet has moved to the server thread). Configuration-
phase dialogs are not supported.

## Why not `run_command`

The vanilla `run_command` action in 1.21.6 shows a confirmation screen to a player who lacks permission for the command,
and would make every dialog button a chat command to guard. `dialog.command(label, cmd)` is a `custom` click that the
server answers with `player:runCommand(cmd)`: the player's own command source, so their permissions, and no confirmation
screen. `mc.execute(cmd, { as = player })` is not used for this: it keeps the console's permissions.

## After actions

The vanilla `after_action` is per dialog; the library offers it per button. `close` is used only
when every button is a server button that wants it; in every other case the dialog stays open on the client (`none`) and
the server closes it with `closeDialog` after the handler, unless the handler showed another dialog.
`wait_for_response` is never used: the client's `ClientboundClearDialogPacket` handler only dismisses dialog and warning
screens, not the "Waiting for response" screen, so a click with no follow-up dialog would leave the player stuck there
(only a new dialog replaces that screen). Escape always closes on the
client; the library only learns about it from the `exit_action`.

## Reload safety

The Lua state is rebuilt on a reload and the library state with it. `uninit` closes the open dialogs and calls `onClose`
with `"reload"`, so no button of the old state stays clickable. `player.leave` clears the player's state.

## Pause menu

Dialog registries and tags are synchronised when a player joins, so a script cannot add to `pause_screen_additions` or
`quick_actions` at runtime. With `menu.enabled` in `config/pxignis.json`, `MenuPack` generates a data pack under
`config/pxignis/datapacks` (from the configured title and button) and a mixin on `PackRepository` offers it next to the
server's other packs; it is enabled automatically like a built-in pack. Both tags are extended (`replace: false`). This
needs no per-version resources besides the pack format number, read from the running game. The flag defaults to off. The file is read once per server start: vanilla `/reload` reloads tags but not the dialog
registry, so a pack switched on by `/reload` produced tag errors (missing `pxignis:menu`); the decision is now fixed per
pack repository and changes apply on restart. The button sends the custom click `pxignis:menu`.
