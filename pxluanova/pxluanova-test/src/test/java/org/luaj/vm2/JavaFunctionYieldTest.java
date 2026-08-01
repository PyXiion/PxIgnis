package org.luaj.vm2;

import junit.framework.TestCase;

import java.util.concurrent.atomic.AtomicReference;

import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Tests that a Java function (VarArgFunction) can call state.yield() from inside
 * a Lua coroutine and have resume values propagate through correctly.
 *
 * Regression test for the FrameInterpreter.run() resume-handler bug where
 * a fresh lua_yield_sync (with resumeArgs == NONE) was mistaken for a resume.
 */
public class JavaFunctionYieldTest extends TestCase {

	LuaState state;

	protected void setUp() throws Exception {
		state = JsePlatform.standardState();
	}

	/**
	 * A Java function that yields and returns the resume value.
	 */
	private static class YieldAndReturnFunction extends VarArgFunction {
		final LuaState state;
		YieldAndReturnFunction(LuaState state) { this.state = state; }

		public Varargs invoke(Varargs args) {
			Varargs resumeArgs = state.yield(args.arg1());
			return resumeArgs;
		}
	}

	/**
	 * A Java function that yields with a value and returns whatever is passed
	 * on resume. The "initial" yield value is visible to the first resumer.
	 */
	private static class YieldAndSwapFunction extends VarArgFunction {
		final LuaState state;
		YieldAndSwapFunction(LuaState state) { this.state = state; }

		public Varargs invoke(Varargs args) {
			state.yield(LuaValue.valueOf("yielded"));
			return LuaValue.valueOf("done");
		}
	}

	public void testJavaFunctionYieldAndResumeReturnsValue() {
		// Register the Java function in the environment
		LuaValue yieldFn = new YieldAndReturnFunction(state);
		state.globals.set("yield_fn", yieldFn);

		// Lua coroutine that calls yield_fn with an argument, then returns the result
		LuaValue func = state.load(
			"local result = yield_fn('call_arg')\n" +
			"return result", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);

		// First resume — the Java function yields
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue("expected resume success", result.arg1().toboolean());
		// The yield value is 'call_arg' (the arg passed to yield_fn)
		assertEquals("call_arg", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		// Second resume — the Java function receives the resume arg and returns it
		result = co.resume(LuaValue.valueOf("resume_val"));
		assertTrue("expected resume success", result.arg1().toboolean());
		// The Java function returned the resume val; Lua returns it
		assertEquals("resume_val", result.arg(2).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testJavaFunctionYieldWithValue() {
		// Register the Java function
		LuaValue yieldFn = new YieldAndSwapFunction(state);
		state.globals.set("yield_fn", yieldFn);

		LuaValue func = state.load(
			"local result = yield_fn('x')\n" +
			"return result", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);

		// First resume — Java function yields with "yielded"
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("yielded", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		// Second resume — the resume values become the return of the call site,
		// taking precedence over the Java function's explicit return value.
		result = co.resume(LuaValue.valueOf("ignored"));
		assertTrue(result.arg1().toboolean());
		assertEquals("ignored", result.arg(2).tojstring());
		assertEquals("dead", co.getStatus());
	}

	/**
	 * A Java function that yields once per invocation.
	 * A single invoke() that calls state.yield multiple times is not supported
	 * (lua_yield_sync sets a flag but doesn't truly suspend), so this test
	 * exercises multi-yield at the Lua level: Lua calls the same Java function
	 * multiple times, each call yields once.
	 */
	private static class SingleYieldFunction extends VarArgFunction {
		final LuaState state;
		SingleYieldFunction(LuaState state) { this.state = state; }

		public Varargs invoke(Varargs args) {
			return state.yield(LuaValue.valueOf("yield_val"));
		}
	}

	public void testJavaFunctionMultipleYieldsViaLuaCalls() {
		LuaValue yieldFn = new SingleYieldFunction(state);
		state.globals.set("yield_fn", yieldFn);

		LuaValue func = state.load(
			"local r1 = yield_fn()\n" +
			"local r2 = yield_fn()\n" +
			"return r1, r2", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);

		// First call to yield_fn: yields
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("yield_val", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		// Second call to yield_fn: yields
		result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("yield_val", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		// After both calls complete, Lua returns r1, r2
		result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("yield_val", result.arg(2).tojstring());
		assertEquals("yield_val", result.arg(3).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testJavaFunctionYieldDoesNotCrashMainThread() {
		try {
			state.yield(LuaValue.NONE);
			fail("Expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage().contains("cannot yield main thread"));
		}
	}

	public void testClosureErrorWithoutLuaThreadDoesNotNPE() {
		LuaValue func = state.load("error('test-error-123')", "test").checkfunction();
		AtomicReference<Throwable> caught = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				func.call();
			} catch (Throwable ex) {
				caught.set(ex);
			}
		});
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("interrupted while joining test thread");
		}
		assertNotNull("expected an exception", caught.get());
		assertTrue(caught.get() instanceof LuaError);
		assertTrue(caught.get().getMessage().contains("test-error-123"));
	}

	public void testClosureErrorWithDebuglibWithoutLuaThreadDoesNotNPE() {
		LuaState debugState = JsePlatform.debugState();
		LuaValue func = debugState.load("error('dbg-error')", "test").checkfunction();
		AtomicReference<Throwable> caught = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				func.call();
			} catch (Throwable ex) {
				caught.set(ex);
			}
		});
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("interrupted while joining test thread");
		}
		assertNotNull("expected an exception", caught.get());
		assertTrue(caught.get() instanceof LuaError);
		assertTrue(caught.get().getMessage().contains("dbg-error"));
	}
}
