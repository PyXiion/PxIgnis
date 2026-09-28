package ru.pyxiion.ignis

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaState
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaTransfer
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform
import ru.pyxiion.ignis.api.AsyncExecutor
import ru.pyxiion.ignis.api.AsyncExecutorRegistry
import ru.pyxiion.ignis.api.AsyncLib

/** Threadpool tasks run in their own Lua state; values cross by copy. */
class AsyncIsolationTest {
    private val mainExec = Executors.newSingleThreadExecutor { Thread(it, "iso-main") }
    private val pool = Executors.newFixedThreadPool(2) { Thread(it, "iso-pool") }
    private val registry = AsyncExecutorRegistry().apply {
        register(AsyncExecutor("main", { mainExec.execute(it) }))
        register(AsyncExecutor("threadpool", { pool.execute(it) }, isolated = true))
    }
    private val main: LuaState = newState()
    private val scheduler = Scheduler { main }

    init {
        lateinit var isolation: AsyncLib.Isolation
        isolation = AsyncLib.Isolation(main, LuaTransfer.NO_USERDATA) { newState().also { install(it, isolation) } }
        install(main, isolation)
        main.globals.set("secret", LuaValue.valueOf(42))
        main.globals.set("userdata", luaFunction { _ -> LuaValue.userdataOf(Any()) })
    }

    private fun newState(): LuaState = JsePlatform.standardState().also {
        // A host function present in every state, like the libraries PxIgnis installs.
        it.globals.set("threadName", luaFunctionZero { LuaValue.valueOf(Thread.currentThread().name) })
    }

    private fun install(state: LuaState, isolation: AsyncLib.Isolation) {
        val lib = AsyncLib(registry, state, scheduler, isolation)
        state.globals.get("package").get("loaded").set("async", lib.buildModule())
    }

    @AfterTest
    fun tearDown() {
        registry.shutdown()
    }

    /** Runs [script] as a coroutine on the main executor, ticking the scheduler, and returns its results. */
    private fun run(script: String): Varargs {
        val done = CompletableFuture<Varargs>()
        fun step(co: LuaThread, args: Varargs) {
            LuaState.setCurrent(main)
            val r = co.resume(args)
            if (co.status == "dead") {
                if (r.arg1().toboolean()) done.complete(r.subargs(2))
                else done.completeExceptionally(LuaError(r.arg(2).tojstring()))
            }
        }
        mainExec.execute {
            LuaState.setCurrent(main)
            val co = LuaThread(main, main.load("local async = require 'async'\n$script", "test"))
            co.executionContext = registry.resolve("main")
            co.resumeHandler = LuaThread.ResumeHandler { t, args -> mainExec.execute { step(t, args) } }
            step(co, LuaValue.NONE)
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!done.isDone) {
            check(System.currentTimeMillis() < deadline) { "script did not finish" }
            mainExec.execute { scheduler.tick() }
            Thread.sleep(2)
        }
        return done.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `results are copied back to the caller`() {
        val r = run(
            """
            local t = async.task("threadpool", function(a) return { sum = a.x + 1 }, "ok" end, { x = 1 })
            local res, s = t:wait()
            return res.sum, s
            """
        )
        assertEquals(2, r.arg1().toint())
        assertEquals("ok", r.arg(2).tojstring())
    }

    @Test
    fun `task runs on the pool in a state without the caller's globals`() {
        val r = run("""return async.run("threadpool", function() return secret, threadName() end)""")
        assertTrue(r.arg1().isnil())
        assertTrue(r.arg(2).tojstring().startsWith("iso-pool"), r.arg(2).tojstring())
    }

    @Test
    fun `upvalues are copies`() {
        val r = run(
            """
            local data = { 1, 2, 3 }
            local n = async.run("threadpool", function() data[1] = 100 return #data end)
            return n, data[1]
            """
        )
        assertEquals(3, r.arg1().toint())
        assertEquals(1, r.arg(2).toint())
    }

    @Test
    fun `library functions captured as upvalues keep working`() {
        val r = run("""local floor = math.floor return async.run("threadpool", function() return floor(2.5) end)""")
        assertEquals(2, r.arg1().toint())
    }

    @Test
    fun `worker can sleep and run work on main`() {
        val r = run(
            """
            return async.run("threadpool", function()
                async.sleep(1)
                return async.run("main", function() return secret, threadName() end)
            end)
            """
        )
        assertEquals(42, r.arg1().toint())
        assertEquals("iso-main", r.arg(2).tojstring())
    }

    @Test
    fun `errors in the worker reach the caller`() {
        val r = run("""return async.task("threadpool", function() error("boom") end):try()""")
        assertFalse(r.arg1().toboolean())
        assertTrue(r.arg(2).tojstring().contains("boom"), r.arg(2).tojstring())
    }

    @Test
    fun `async objects cannot be sent`() {
        val r = run(
            """
            local p = async.promise()
            return pcall(async.task, "threadpool", function() return p end)
            """
        )
        assertFalse(r.arg1().toboolean())
        assertTrue(r.arg(2).tojstring().contains("upvalue 'p' of the task function"), r.arg(2).tojstring())
    }

    @Test
    fun `userdata arguments are refused by the policy`() {
        val r = run("""return pcall(async.task, "threadpool", function() end, userdata())""")
        assertFalse(r.arg1().toboolean())
        assertTrue(r.arg(2).tojstring().contains("task argument #1"), r.arg(2).tojstring())
    }

    @Test
    fun `results that cannot be sent fail the task`() {
        val r = run("""return async.task("threadpool", function() return coroutine.create(print) end):try()""")
        assertFalse(r.arg1().toboolean())
        assertTrue(r.arg(2).tojstring().contains("task result #1"), r.arg(2).tojstring())
    }

    @Test
    fun `main tasks from main still share state`() {
        val r = run(
            """
            local t = {}
            local same = async.run("main", function() return t end)
            return same == t
            """
        )
        assertTrue(r.arg1().toboolean())
    }
}
