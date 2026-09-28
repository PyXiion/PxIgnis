#!/usr/bin/env bash
# Checks the arena tutorial: the snippets in the pages match the checkpoint files, LuaLS finds no problems
# in them, and the game logic runs against a fake API (test/mock.lua).
# Needs python3, lua5.2 and lua-language-server (set LUALS to its path if it is not on PATH).
set -euo pipefail
cd "$(dirname "$0")"
LUALS=${LUALS:-lua-language-server}

python3 check-snippets.py

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failed=0
for file in arena/*.lua; do
    ws="$work/$(basename "$file" .lua)"
    mkdir -p "$ws"
    cp -r ../../lua-types "$ws/.types"
    cp "$file" "$ws/"
    cat > "$ws/.luarc.json" <<'JSON'
{
  "runtime.version": "Lua 5.2",
  "runtime.builtin": { "io": "disable", "os": "disable", "debug": "disable" },
  "workspace.library": [".types"]
}
JSON
    "$LUALS" --check="$ws" --checklevel=Hint --check_format=json --check_out_path="$ws/check.json" \
        --logpath="$ws/log" >/dev/null 2>&1 || true
    if [ ! -f "$ws/check.json" ]; then
        echo "$file: LuaLS produced no report"; failed=1
    elif [ "$(tr -d '[:space:]' < "$ws/check.json")" != "[]" ]; then
        echo "$file: LuaLS problems:"; cat "$ws/check.json"; failed=1
    fi
done
[ "$failed" = 0 ] && echo "LuaLS: no problems"

for n in 1 2 3 4 5 6 7 8 9; do
    file=$(ls arena/0$n-*.lua | grep -v rewards)
    lua5.2 test/smoke.lua test "$file" "$n"
done
lua5.2 test/scenario.lua test arena/09-final.lua
lua5.2 test/scenario.lua test arena/09-final.lua arena/08-rewards.lua

exit "$failed"
