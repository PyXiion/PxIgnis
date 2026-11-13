# ignis-mapcanvas

Plugin `plugins/mapcanvas`, module `ext:mapcanvas`. Lua API: `plugins/mapcanvas/src/main/resources/lua-types/mapcanvas.lua`.

- A canvas is a virtual map: id from 1 000 000 000 (a process-wide counter, never reset on reload), a 128x128
  palette-byte buffer, icons. Nothing is saved into the world. Vanilla only reads `MapItemSavedData` for ids that exist,
  so holding the item does not create or overwrite data.
- Sending: once per 50 ms tick, per viewer, one `map_item_data` packet holding the bounding rectangle of the pixels that
  changed (plus the icons when they changed). A new viewer, a respawned player object or a world change gets the full image.
- Walls: packet-only invisible item frames (never added to the level). Players in the wall's world within 48 blocks are
  shown the frames and the canvases automatically (checked every 10 ticks); `wall:remove()` and reload send remove packets.
- Pure logic (palette, clipping, dirty rectangle) is in `Palette.kt` / `PixelBuffer.kt` and unit-tested without Minecraft.
- Smoke: `plugins/mapcanvas/smoke/run.sh [target]`. Held-map and wall rendering need a real client and are not covered.
