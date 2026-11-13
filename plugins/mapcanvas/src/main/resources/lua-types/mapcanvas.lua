---@meta ext:mapcanvas

-- ignis-mapcanvas: virtual 128x128 map canvases sent to players with map_item_data packets, and packet-only
-- invisible item-frame walls. Canvas ids start at 1000000000 and are never saved into the world.
-- local mapcanvas = require("ext:mapcanvas")

---Palette byte: baseColor * 4 + brightness. 0..3 is transparent.
---@alias MapColorByte integer

---@class MapIcon
---@field type string   # vanilla MapDecorationType id: player, frame, red_marker, blue_marker, target_x, target_point, player_off_map, player_off_limits, mansion, monument, white_banner, ...
---@field x integer     # -128..127 across the whole map (0 = centre)
---@field z integer     # -128..127 across the whole map (0 = centre)
---@field rot? integer  # 0..15, default 0
---@field name? string

---@class MapWall
local MapWall = {}

---Sends remove packets to every viewer and forgets the wall.
function MapWall:remove() end

---@class MapCanvas
---@field id integer              # virtual map id
---@field viewers Player[]        # players shown this canvas by :show (online ones)
local MapCanvas = {}

---All drawing is clipped to 128x128.
---@param x integer
---@param y integer
---@param w integer
---@param h integer
---@param color MapColorByte
function MapCanvas:fill(x, y, w, h, color) end

---@param x integer
---@param y integer
---@param color MapColorByte
function MapCanvas:pixel(x, y, color) end

---`bytes` holds w*h palette bytes, row-major.
---@param x integer
---@param y integer
---@param w integer
---@param h integer
---@param bytes string
function MapCanvas:blit(x, y, w, h, bytes) end

---@param color? MapColorByte  # default 0 (transparent)
function MapCanvas:clear(color) end

---Replaces all icons.
---@param list MapIcon[]
function MapCanvas:icons(list) end

---Shows the canvas to `player`: they get the full image, then patches. Hold `:item()` to see it.
---@param player Player
function MapCanvas:show(player) end

---@param player Player
function MapCanvas:hide(player) end

---Sends pending changes now. Otherwise this happens at the end of every tick: one patch (the bounding rectangle of
---changed pixels) per viewer, plus the icons when they changed.
function MapCanvas:flush() end

---A filled_map whose map_id is this canvas.
---@param name? string  # custom name
---@return Item
function MapCanvas:item(name) end

---Packet-only invisible item frames. `pos` is the block of the top-left frame as seen by a viewer, `facing` the
---direction the frames face (north/south/east/west). `canvases` are row-major from the top-left, cols*rows of them.
---Players in `world` within 48 blocks see the wall automatically.
---@param world World
---@param pos vec
---@param facing "north"|"south"|"east"|"west"
---@param cols integer
---@param rows integer
---@param canvases MapCanvas[]
---@return MapWall
function MapCanvas:wall(world, pos, facing, cols, rows, canvases) end

---Stops sending and forgets the canvas. Its viewers keep the last image they got.
function MapCanvas:free() end

---@class mapcanvas
local mapcanvas = {}

---A new transparent canvas.
---@return MapCanvas
function mapcanvas.canvas() end

---Palette byte for a vanilla MapColor name (grass, stone, water, color_red, ... also red, green, blue, gray, white).
---`shade` is the vanilla brightness id, default 1: 0 = x180/255, 1 = x220 (normal), 2 = x255 (brightest), 3 = x135 (darkest).
---@param name string
---@param shade? integer
---@return MapColorByte
function mapcanvas.color(name, shade) end

---Palette byte nearest to an RGB colour (never transparent).
---@param r integer
---@param g integer
---@param b integer
---@return MapColorByte
function mapcanvas.rgb(r, g, b) end

return mapcanvas
