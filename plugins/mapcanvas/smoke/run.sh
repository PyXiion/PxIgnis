#!/usr/bin/env bash
# ignis-mapcanvas smoke: boots real Fabric servers (smoke/run.sh) with the core jar and the plugin jar.
#
#   plugins/mapcanvas/smoke/run.sh [target...]       default: every gradle/versions/*.properties
#
# Checks the "[SMOKE] mapcanvas ..." lines of mapcanvas-smoke.lua and the server log for errors. Packets need a real
# client to be seen, so rendering of held maps and walls is not covered.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
export MINECRAFT_EULA=${MINECRAFT_EULA:-true}

prop() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
    for f in "$root"/gradle/versions/*.properties; do targets+=("$(basename "$f" .properties)"); done
fi

status=0
for t in "${targets[@]}"; do
    mod_version=$(prop "$root/gradle.properties" mod_version)
    (cd "$root" && JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")") ./gradlew :plugins:mapcanvas:assemble -q -PtargetVersion="$t")
    plugin="$root/plugins/mapcanvas/build/libs/ignis-mapcanvas-$mod_version-fabric-$t.jar"
    echo "mapcanvas-smoke[$t]"
    log="$root/build/smoke/$t.log"
    if ! PXIGNIS_SMOKE_MODS="$plugin" PXIGNIS_SMOKE_EXTRA="$here/mapcanvas-smoke.lua" "$root/smoke/run.sh" "$t"; then status=1; fi
    cp "$log" "$root/build/smoke/mapcanvas-$t.log"
    n_ok=$(grep -c '^\[SMOKE\] mapcanvas .*: OK$' "$log" || true)
    grep -q '^\[SMOKE\] mapcanvas done' "$log" || { echo "mapcanvas-smoke[$t]: did not finish" >&2; status=1; }
    grep -E '^\[SMOKE\] mapcanvas .*FAIL' "$log" >&2 && status=1
    grep -E 'NoClassDefFoundError|ClassNotFoundException|mapcanvas.*(Exception|error)' "$log" >&2 && status=1
    echo "mapcanvas-smoke[$t]: $n_ok mapcanvas checks OK"
done
exit $status
