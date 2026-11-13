#!/usr/bin/env bash
# ignis-voice smoke: boots real Fabric servers (smoke/run.sh) with the core jar, the plugin jar and, in the first
# pass, Simple Voice Chat; then again without it. Needs ffmpeg (fixtures) and curl.
#
#   plugins/voice/smoke/run.sh [target...]       default: every gradle/versions/*.properties
#
# Checks: the "[SMOKE] voice ..." lines of voice-smoke.lua, no server errors, reload (smoke/ignis-smoke.lua reloads
# twice). Without SVC, voice.backends is empty and nothing may fail to load. Audible playback needs a real client and
# is not covered.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
cache=${PXIGNIS_SMOKE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pxignis-smoke}
svc_version=2.6.27
export MINECRAFT_EULA=${MINECRAFT_EULA:-true}

prop() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
    for f in "$root"/gradle/versions/*.properties; do targets+=("$(basename "$f" .properties)"); done
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/ignis-voice-smoke.XXXXXX")
trap 'rm -rf "$work"' EXIT
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=1" "$work/voice-tone.wav"
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=330:sample_rate=22050:duration=0.5" -ac 2 -c:a libvorbis "$work/voice-tone.ogg"
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=220:sample_rate=8000:duration=35" "$work/voice-long.wav"
ffmpeg -loglevel error -y -f lavfi -i "color=c=red:s=32x32:d=1" -frames:v 1 "$work/voice-icon.png"

status=0
for t in "${targets[@]}"; do
    props="$root/gradle/versions/$t.properties"
    mc=$(prop "$props" minecraft_version)
    mod_version=$(prop "$root/gradle.properties" mod_version)
    (cd "$root" && JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")") \
        ./gradlew :plugins:voice:assemble -q -PtargetVersion="$t")
    plugin="$root/plugins/voice/build/libs/ignis-voice-$mod_version-fabric-$t.jar"

    mkdir -p "$cache/voice"
    url=$(curl -fsS "https://api.modrinth.com/v2/project/simple-voice-chat/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22$mc%22%5D" \
        | python3 -c "import json,sys; v=[x for x in json.load(sys.stdin) if '$svc_version' in x['version_number']]; print(v[0]['files'][0]['url'])")
    svc="$cache/voice/voicechat-$t-$svc_version.jar"
    [[ -s $svc ]] || curl -fsSL -o "$svc" "$url"

    for variant in svc nosvc; do
        dir="$work/$t-$variant"
        mkdir -p "$dir"
        cp "$work"/voice-tone.wav "$work"/voice-tone.ogg "$work"/voice-long.wav "$work"/voice-icon.png "$dir/"
        sed "s/VOICE_EXPECT_SVC/$([[ $variant == svc ]] && echo true || echo false)/" "$here/voice-smoke.lua" > "$dir/voice-smoke.lua"
        mods="$plugin"; [[ $variant == svc ]] && mods="$plugin $svc"
        echo "voice-smoke[$t/$variant]"
        log="$root/build/smoke/$t.log"
        if ! PXIGNIS_SMOKE_MODS="$mods" PXIGNIS_SMOKE_EXTRA="$dir/voice-smoke.lua $dir/voice-tone.wav $dir/voice-tone.ogg $dir/voice-long.wav $dir/voice-icon.png" \
            "$root/smoke/run.sh" "$t"; then status=1; fi
        cp "$log" "$root/build/smoke/voice-$t-$variant.log"
        n_ok=$(grep -c '^\[SMOKE\] voice .*: OK$' "$log" || true)
        want=$([[ $variant == svc ]] && echo svc || echo none)
        grep -q "^\[SMOKE\] voice backends: ${want}$" "$log" || { echo "voice-smoke[$t/$variant]: expected backends '$want'" >&2; status=1; }
        grep -qE '^\[SMOKE\] voice (await|onReady): OK' "$log" || { echo "voice-smoke[$t/$variant]: await/onReady did not report" >&2; status=1; }
        grep -E '^\[SMOKE\] voice .*FAIL' "$log" >&2 && status=1
        grep -E 'NoClassDefFoundError|ClassNotFoundException' "$log" >&2 && status=1
        echo "voice-smoke[$t/$variant]: $n_ok voice checks OK"
    done
done
exit $status
