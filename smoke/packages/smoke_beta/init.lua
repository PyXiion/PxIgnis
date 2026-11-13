-- Smoke fixture: depends on smoke_alpha, requires it through pkg: and its own file through a bare name.
local alpha = require("pkg:smoke_alpha")
local helper = require("lib.helper")
SMOKE_PKG_ORDER = (rawget(_G, "SMOKE_PKG_ORDER") or "") .. "beta,"
-- Only with the smoketest plugin installed (smoke/README in testing.md): the plugin resolves files against this package.
local ok, ext = pcall(require, "ext:smoketest")
if ok then SMOKE_EXT_FILE = ext.file("lib/helper.lua") end
return { alpha = alpha.name, helper = helper, same = helper == require("pkg:smoke_beta.lib.helper") }
