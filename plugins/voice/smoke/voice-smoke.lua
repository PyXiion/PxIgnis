-- ignis-voice smoke checks, run alongside smoke/ignis-smoke.lua by plugins/voice/smoke/run.sh.
-- VOICE_EXPECT_SVC is replaced by the runner with true or false.
local EXPECT_SVC = VOICE_EXPECT_SVC
local function step(name, fn)
    local ok, err = pcall(fn)
    print("[SMOKE] voice " .. name .. ": " .. (ok and "OK" or ("FAIL " .. tostring(err))))
end

local voice = require("ext:voice")

step("backends", function()
    assert(#voice.backends == (EXPECT_SVC and 1 or 0), "backends: " .. table.concat(voice.backends, ","))
    assert(not EXPECT_SVC or voice.backends[1] == "svc")
    print("[SMOKE] voice backends: " .. (#voice.backends == 0 and "none" or table.concat(voice.backends, ",")))
end)

step("category", function()
    voice.category { id = "smoke_music", name = "Smoke", description = "smoke test", icon = "voice-icon.png" }
    voice.category { id = "smoke_plain" }
    assert(not pcall(voice.category, { id = "Bad Id" }))
    assert(not pcall(voice.category, { name = "no id" }))
    assert(not pcall(voice.category, { id = "x", icon = "does-not-exist.png" }))
end)

local wav, ogg, long
step("load", function()
    wav = voice.load("voice-tone.wav")
    ogg = voice.load("voice-tone.ogg")
    long = voice.load("voice-long.wav")
    assert(type(wav.ready) == "boolean" and wav.error == nil)
    assert(not pcall(voice.load, "voice-missing.wav"))
    assert(not pcall(voice.load, "../../escape.wav"))
end)

local function sources(w)
    return {
        { pos = vec(0, 80, 0), world = w, distance = 16, category = "smoke_music", volume = 0.5 },
        { players = {} },
    }
end

step("emitter options", function()
    local w = mc.world("overworld")
    assert(not pcall(voice.emitter, {}))
    assert(not pcall(voice.emitter, { pos = vec(0, 0, 0) }))
    assert(not pcall(voice.emitter, { pos = vec(0, 0, 0), world = w, players = {} }))
    assert(not pcall(voice.emitter, { players = {}, category = "undeclared" }))
    assert(not pcall(voice.emitter, { players = {}, bogus = 1 }))
end)

mc.schedule(60, function()
    step("sounds ready", function()
        for _, s in ipairs { wav, ogg, long } do
            assert(s.ready and s.error == nil, "not ready: " .. tostring(s.error))
        end
        assert(math.abs(wav.duration - 1.0) < 0.05, "wav duration " .. tostring(wav.duration))
        assert(math.abs(ogg.duration - 0.5) < 0.05, "ogg duration " .. tostring(ogg.duration))
        assert(math.abs(long.duration - 35.0) < 0.1, "long duration " .. tostring(long.duration))
        print(string.format("[SMOKE] voice durations: wav=%.3f ogg=%.3f long=%.3f", wav.duration, ogg.duration, long.duration))
    end)

    step("emitters", function()
        local w = mc.world("overworld")
        local ended = false
        for _, spec in ipairs(sources(w)) do
            local e = voice.emitter(spec)
            e:onEnd(function() ended = true end)
            e:play(wav, { loop = false })
            e:queue(ogg)
            assert(e.position >= 0)
            e:stop()
            e:play(long, { loop = true })
            e:stop()
            e:close()
            e:close()
            assert(not pcall(e.play, e, wav))
        end
        local pig = w:spawn("minecraft:pig", vec(2, 80, 2), {})
        local e = voice.emitter { entity = pig, distance = 8, listeners = function(p) return true end }
        e:play(wav)
        e.entity = pig
        e:close()
        local h = voice.play(wav, { pos = vec(0, 80, 0), world = w, distance = 16 })
        h:stop()
        pig:remove()
    end)

    local async = require("async")
    async.task(function()
        local s = voice.load("voice-tone.wav")
        local r = s:await()
        print("[SMOKE] voice await: " .. ((r.ready and s.ready) and "OK" or "FAIL not ready"))
        local ready = false
        s:onReady(function(x) ready = x.ready end)
        mc.schedule(3, function()
            print("[SMOKE] voice onReady: " .. (ready and "OK" or "FAIL never called"))
        end)
    end)
end)

-- The script root is not a coroutine: await must fail there, not hang.
step("await in the script root fails", function()
    local ok, err = pcall(function() voice.load("voice-tone.wav"):await() end)
    assert(not ok and tostring(err):find("main thread", 1, true), tostring(err))
end)

-- A loop that has been playing into the reload is stopped by it: this emitter must not outlive the script run.
local rel = voice.emitter { players = {} }
rel:play(long, { loop = true })
mc.schedule(100, function()
    step("emitter created before the voice server started is playing", function()
        assert(rel.playing == (#voice.backends > 0), "playing=" .. tostring(rel.playing))
        assert(not EXPECT_SVC or rel.position > 0, "position " .. tostring(rel.position))
    end)
end)
