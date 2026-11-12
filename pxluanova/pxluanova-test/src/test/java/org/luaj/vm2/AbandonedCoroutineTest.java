package org.luaj.vm2;

import java.lang.ref.WeakReference;

import junit.framework.TestCase;

import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * A suspended coroutine that is never resumed again holds no Java thread, so dropping the last reference
 * must be enough for it (and its function) to be garbage collected.
 */
public class AbandonedCoroutineTest extends TestCase {

	private LuaState state;

	protected void setUp() {
		state = JsePlatform.standardState();
	}

	public void testSuspendedClosureIsCollected() throws Exception {
		assertCollectedAfterTwoResumes(
			"local arg = coroutine.yield(1)\n" +
			"arg = coroutine.yield(0)\n" +
			"return 'never reached'\n", LuaValue.ZERO);
	}

	public void testSuspendedInsidePcallIsCollected() throws Exception {
		assertCollectedAfterTwoResumes(
			"local function f(x)\n" +
			"  local arg = coroutine.yield(1)\n" +
			"  arg = coroutine.yield(0)\n" +
			"  return 'never reached'\n" +
			"end\n" +
			"return pcall(f, ...)\n", LuaValue.ZERO);
	}

	public void testYieldInsideLoadReaderIsAnError() {
		// As in Lua 5.2: the reader cannot yield, and load reports the error as nil, message.
		LuaThread t = new LuaThread(state, state.load(
			"return load(function() coroutine.yield(1) end)", "script"));
		Varargs r = t.resume(LuaValue.NONE);
		assertTrue(r.arg1().toboolean());
		assertTrue(r.arg(2).isnil());
		assertTrue(r.arg(3).tojstring(), r.arg(3).tojstring().contains("attempt to yield across a C-call boundary"));
		assertEquals("dead", t.getStatus());
	}

	private void assertCollectedAfterTwoResumes(String script, LuaValue secondYield) throws Exception {
		LuaValue function = state.load(script, "script");
		LuaThread thread = new LuaThread(state, function);
		WeakReference<LuaThread> threadRef = new WeakReference<>(thread);
		WeakReference<LuaValue> funcRef = new WeakReference<>(function);

		Varargs a = thread.resume(LuaValue.valueOf("foo"));
		assertEquals(LuaValue.TRUE, a.arg1());
		assertEquals(LuaValue.ONE, a.arg(2));
		a = thread.resume(LuaValue.valueOf("bar"));
		assertEquals(LuaValue.TRUE, a.arg1());
		assertEquals(secondYield, a.arg(2));
		assertEquals("suspended", thread.getStatus());

		thread = null;
		function = null;
		for (int i = 0; i < 100 && (threadRef.get() != null || funcRef.get() != null); i++) {
			System.gc();
			Thread.sleep(5);
		}
		assertNull(threadRef.get());
		assertNull(funcRef.get());
	}
}
