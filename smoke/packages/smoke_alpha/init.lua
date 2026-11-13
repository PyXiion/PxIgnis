-- Smoke fixture: a package without dependencies.
SMOKE_PKG_ORDER = (rawget(_G, "SMOKE_PKG_ORDER") or "") .. "alpha,"
return { name = "alpha" }
