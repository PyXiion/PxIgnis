#!/usr/bin/env bash
# Smoke suite: boots a real Fabric server with the production jar and smoke/ignis-smoke.lua, per MC target.
#
#   smoke/run.sh [all|<target>...]     targets: gradle/versions/*.properties (default: all)
#
# Isolation: every run gets a throwaway server dir under $TMPDIR (flat world, removed on exit), so nothing touches
# run/ and targets cannot clash over a world. Downloads (Fabric launcher, vanilla server, libraries, Fabric API,
# Fabric Language Kotlin) are cached once per target in $PXIGNIS_SMOKE_CACHE (default ~/.cache/pxignis-smoke).
# The jar under test is build/libs/PxIgnis-<version>-fabric-<target>.jar; it is rebuilt only when a source file is
# newer than it, so repeated runs do not recompile. The log of each target is kept in build/smoke/<target>.log.
#
# Needs the Minecraft EULA accepted: eula=true in run/eula.txt, or MINECRAFT_EULA=true in the environment.
# PXIGNIS_SMOKE_EXTRA="a.lua b.lua" copies extra scripts next to the smoke script (for one-off checks).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cache=${PXIGNIS_SMOKE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pxignis-smoke}
installer=${PXIGNIS_SMOKE_INSTALLER:-1.1.2}
timeout_s=${PXIGNIS_SMOKE_TIMEOUT:-240}
maven=https://maven.fabricmc.net

prop() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }

find_java() { # find_java <major>: a JDK of at least that major version
    local want=$1 c
    for c in "/usr/lib/jvm/java-$want-openjdk/bin/java" "/usr/lib/jvm/java-$want-openjdk-amd64/bin/java" \
        "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
        [[ -n $c && -x $c ]] || continue
        local major
        major=$("$c" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
        [[ ${major:-0} -ge $want ]] && { echo "$c"; return; }
    done
    echo "smoke: no JDK >= $want found" >&2
    return 1
}

if ! grep -qs '^eula=true' "$root/run/eula.txt" && [[ ${MINECRAFT_EULA:-} != true ]]; then
    echo "smoke: accept the Minecraft EULA (https://aka.ms/MinecraftEULA) first: eula=true in run/eula.txt, or MINECRAFT_EULA=true" >&2
    exit 2
fi

targets=("$@")
if [[ ${#targets[@]} -eq 0 || ${targets[0]} == all ]]; then
    targets=()
    for f in "$root"/gradle/versions/*.properties; do targets+=("$(basename "$f" .properties)"); done
fi

mod_version=$(prop "$root/gradle.properties" mod_version)

fetch() { # fetch <url> <file>: download once
    [[ -s $2 ]] && return
    mkdir -p "$(dirname "$2")"
    echo "smoke: downloading $(basename "$2")"
    curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}

build_jar() { # build_jar <target> -> prints jar path
    local t=$1 jar="$root/build/libs/PxIgnis-$mod_version-fabric-$t.jar"
    if [[ ! -f $jar ]] || [[ -n $(find "$root/src" "$root/pxluanova" "$root/gradle" "$root/build.gradle" "$root/gradle.properties" \
        \( -name build -o -name .gradle \) -prune -o -type f -newer "$jar" -print -quit) ]]; then
        echo "smoke: building $t" >&2
        (cd "$root" && JAVA_HOME=$(dirname "$(dirname "$(find_java 25)")") ./gradlew assemble -q -PtargetVersion="$t" >&2) || return 1
    fi
    echo "$jar"
}

run_target() { # run_target <target>: 0 = all green
    local t=$1 props="$root/gradle/versions/$1.properties"
    [[ -f $props ]] || { echo "smoke: unknown target '$t'" >&2; return 1; }
    local mc loader fabric flk java jar
    mc=$(prop "$props" minecraft_version); loader=$(prop "$props" loader_version)
    fabric=$(prop "$props" fabric_version); flk=$(prop "$props" flk_version)
    java=$(find_java "$(prop "$props" java_version)")
    jar=$(build_jar "$t") || { echo "smoke[$t]: build failed" >&2; return 1; }

    local c="$cache/$t"
    fetch "https://meta.fabricmc.net/v2/versions/loader/$mc/$loader/$installer/server/jar" "$c/fabric-server-launch.jar"
    fetch "$maven/net/fabricmc/fabric-api/fabric-api/$fabric/fabric-api-$fabric.jar" "$c/mods/fabric-api-$fabric.jar"
    fetch "$maven/net/fabricmc/fabric-language-kotlin/$flk/fabric-language-kotlin-$flk.jar" "$c/mods/fabric-language-kotlin-$flk.jar"
    mkdir -p "$c/libraries" "$c/.fabric" "$c/versions"

    local run log="$root/build/smoke/$t.log"
    mkdir -p "$root/build/smoke"
    run=$(mktemp -d "${TMPDIR:-/tmp}/pxignis-smoke-$t.XXXXXX")
    local pid="" fifo="$run/stdin"
    cleanup() { [[ -n $pid ]] && kill "$pid" 2>/dev/null || true; exec 3>&- 2>/dev/null || true; rm -rf "$run"; }
    trap cleanup RETURN

    mkdir -p "$run/mods" "$run/config/ignis"
    ln -s "$c"/libraries "$run/libraries"; ln -s "$c"/.fabric "$run/.fabric"; ln -s "$c"/versions "$run/versions"
    ln -s "$c"/mods/*.jar "$run/mods/"
    cp "$jar" "$run/mods/"
    cp "$root/smoke/ignis-smoke.lua" "$run/config/ignis/"
    for extra in ${PXIGNIS_SMOKE_EXTRA:-}; do cp "$extra" "$run/config/ignis/"; done # extra scripts to run alongside
    echo "eula=true" > "$run/eula.txt"
    cat > "$run/server.properties" <<EOF
online-mode=false
generate-structures=false
view-distance=4
simulation-distance=4
max-tick-time=-1
server-port=$((20000 + RANDOM % 20000))
enable-query=false
enable-rcon=false
EOF

    mkfifo "$fifo"
    exec 3<>"$fifo" # keeps the pipe open so the server never sees EOF
    (cd "$run" && exec "$java" -Xmx768M -jar "$c/fabric-server-launch.jar" nogui <&3 > "$run/server.log" 2>&1) &
    pid=$!

    local waited=0
    until grep -q '^\[SMOKE\] all done' "$run/server.log" 2>/dev/null; do
        kill -0 "$pid" 2>/dev/null || { echo "smoke[$t]: server exited before 'all done'" >&2; break; }
        ((waited++ >= timeout_s * 2)) && { echo "smoke[$t]: timed out after ${timeout_s}s" >&2; break; }
        sleep 0.5
    done
    sleep 4 # async checks (parallel workers) report after "all done"
    echo stop >&3
    for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill "$pid" 2>/dev/null || true
    pid=""
    cp "$run/server.log" "$log"

    local ok fail errors done_
    ok=$(grep -c '^\[SMOKE\] .*: OK$' "$log" || true)
    fail=$(grep '^\[SMOKE\] .*: FAIL' "$log" || true)
    # Errors the smoke script provokes on purpose: a throwing handler (fail-closed events) and a runaway task (watchdog).
    errors=$(grep -E '/(ERROR|FATAL)\]' "$log" | grep -vE 'smoke: expected failure|ignis-smoke\.lua:[0-9]+: script exceeded the' || true)
    done_=$(grep -c '^\[SMOKE\] all done' "$log" || true)

    local status=0
    [[ $done_ -ge 1 ]] || { echo "smoke[$t]: never reached 'all done'" >&2; status=1; }
    [[ -z $fail ]] || { echo "$fail" >&2; status=1; }
    [[ -z $errors ]] || { echo "smoke[$t]: server errors:" >&2; echo "$errors" >&2; status=1; }
    echo "smoke[$t]: $ok OK, $([[ -z $fail ]] && echo 0 || echo "$fail" | wc -l) FAIL, $([[ $status == 0 ]] && echo PASS || echo "FAIL (log: build/smoke/$t.log)")"
    return $status
}

overall=0
for t in "${targets[@]}"; do
    run_target "$t" || overall=1
done
exit $overall
