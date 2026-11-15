package ru.pyxiion.ignis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.pyxiion.ignis.api.AsyncLib
import ru.pyxiion.ignis.sandbox.Vfs
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaThread
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform

/** A suspend call that completes after a script reload must not resume the old state's coroutine. */
class StaleResumeTest {

    private fun completeSuspendCall(invalidateBeforeCompletion: Boolean): Pair<LuaThread, LuaState> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val state = JsePlatform.standardState()
            val asyncLib = AsyncLib({ state }, Scheduler { state }, Vfs(listOf(TestTemp.dir("ignis"))))
            val gate = CompletableDeferred<Unit>()
            val handlerCalled = CountDownLatch(1)
            val inner = asyncLib.newResumeHandler()
            LuaState.setCurrent(state)
            state.globals.set("mc", luaTableOf("wait" to luaSuspendFunction(scope) { _ ->
                gate.await()
                LuaValue.NIL
            }))
            state.globals.set("_after", LuaValue.FALSE)

            val func = state.load("mc.wait(); _after = true", "test").checkfunction()
            val co = LuaThread(state, func)
            co.resumeHandler = LuaThread.ResumeHandler { t, a ->
                inner.resume(t, a)
                handlerCalled.countDown()
            }
            assertTrue(co.resume(LuaValue.NONE).arg1().toboolean())
            assertEquals("suspended", co.status)

            if (invalidateBeforeCompletion) asyncLib.invalidate()
            gate.complete(Unit)
            assertTrue(handlerCalled.await(5, TimeUnit.SECONDS), "resume handler was not invoked")
            asyncLib.drain()
            return co to state
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resume completes the coroutine when scripts were not reloaded`() {
        val (co, state) = completeSuspendCall(invalidateBeforeCompletion = false)
        assertEquals("dead", co.status)
        assertTrue(state.globals.get("_after").toboolean())
    }

    @Test
    fun `resume after script reload is dropped`() {
        val (co, state) = completeSuspendCall(invalidateBeforeCompletion = true)
        assertEquals("suspended", co.status)
        assertTrue(!state.globals.get("_after").toboolean())
    }
}
