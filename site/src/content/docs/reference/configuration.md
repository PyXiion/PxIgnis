---
title: Configuration
description: Files, JVM flags and permissions that configure PxIgnis.
---

## Files

| Path                        | Purpose                                                                        |
|-----------------------------|--------------------------------------------------------------------------------|
| `config/ignis/*.lua`        | Your scripts, loaded in file name order; subfolders hold modules for `require` |
| `config/ignis/storage/`     | Persistent data (`mc.data`, `player.data`), see [storage](/reference/storage)  |
| `config/pxignis.json`       | Mod settings (below)                                                           |
| `config/pxignis/datapacks/` | Generated data pack for the pause menu; do not edit                            |

## `config/pxignis.json`

Created with the defaults on first start. It is read once when the server starts: changes apply after a restart, not on
`/reload` or `/ignis reload`.

```json
{
  "menu": {
    "enabled": false,
    "title": "Server menu",
    "button": "Open"
  }
}
```

| Key            | Type    | Default         | Description                                                                   |
|----------------|---------|-----------------|-------------------------------------------------------------------------------|
| `menu.enabled` | boolean | `false`         | Adds the dialog `pxignis:menu` to the pause screen and the quick actions menu |
| `menu.title`   | string  | `"Server menu"` | Text of the pause-menu button and title of the dialog                         |
| `menu.button`  | string  | `"Open"`        | Label of the button inside the dialog                                         |

When enabled, clicking the button inside the dialog raises `player.custom_click` with the id `pxignis:menu`; see the
[dialog library](/libraries/dialog#pause-menu-button). Both vanilla tags are extended, existing entries stay. A broken
file is ignored with a warning in the log (defaults are used). Dialogs are loaded from the data pack when the server
starts (vanilla `/reload` reloads the tags but not the dialog registry), so a restart is needed for any change, and
players see the button when they join.

## JVM flags

| Flag                          | Description                                                                          |
|-------------------------------|--------------------------------------------------------------------------------------|
| `-Dpxignis.watch=true`        | Reload scripts automatically when a `.lua` file is saved (same as `/ignis watch on`) |
| `-Dpxignis.scriptTimeoutMs=N` | Time limit for one script run in milliseconds, default `5000`, `0` disables it       |

## Permissions

| Node                     | Default | Grants                             |
|--------------------------|---------|------------------------------------|
| `px.ignis`               | op 4    | `/ignis` (reload, status, watch)   |
| `px.ignis.eval`          | op 4    | `/ignis eval`                      |
| `px.ignis.prompt_errors` | op 4    | Script errors and warnings in chat |

Commands registered with `register(...)` take their own permission node as the last argument.
