package ru.pyxiion.luanova.vm2;

import junit.framework.TestCase;

import java.util.concurrent.atomic.AtomicReference;

import ru.pyxiion.luanova.vm2.lib.VarArgFunction;
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform;

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

		// Second call to yield_fn: yields; the first call returns what this resume passed
		result = co.resume(LuaValue.valueOf("first"));
		assertTrue(result.arg1().toboolean());
		assertEquals("yield_val", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		// Each yield_fn call returns the values of the resume that continued it
		result = co.resume(LuaValue.valueOf("second"));
		assertTrue(result.arg1().toboolean());
		assertEquals("first", result.arg(2).tojstring());
		assertEquals("second", result.arg(3).tojstring());
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

	/** A14: CALL at the top of the stack frame must not overflow on resume. */
	public void testResumeAfterYieldAtStackTop() {
		LuaValue f = state.load(
			"local co = coroutine.wrap(function()\n" +
			"  local a, b = 1, 2\n" +
			"  local x = coroutine.yield()\n" +
			"  return a, b, x\n" +
			"end)\n" +
			"co()\n" +
			"return co(7)", "a14");
		Varargs r = f.invoke(LuaValue.NONE);
		assertEquals(3, r.narg());
		assertEquals(1, r.arg(1).toint());
		assertEquals(2, r.arg(2).toint());
		assertEquals(7, r.arg(3).toint());
	}

	/** A14: same shape, yield done by a Java function. */
	public void testResumeAfterJavaYieldAtStackTop() {
		state.globals.set("yield_fn", new YieldAndReturnFunction(state));
		LuaValue f = state.load(
			"local co = coroutine.wrap(function()\n" +
			"  local a, b = 1, 2\n" +
			"  local x = yield_fn('q')\n" +
			"  return a, b, x\n" +
			"end)\n" +
			"co()\n" +
			"return co(7)", "a14j");
		Varargs r = f.invoke(LuaValue.NONE);
		assertEquals(3, r.narg());
		assertEquals(1, r.arg(1).toint());
		assertEquals(2, r.arg(2).toint());
		assertEquals(7, r.arg(3).toint());
	}

	/**
	 * Runs {@code body} inside a coroutine and returns what resume() produced: ok, value.
	 * A yield that would have to unwind through a Java frame must fail loudly instead of
	 * letting the Java function and the rest of the Lua body continue with a half-yield.
	 */
	private Varargs resumeOnce(String body) {
		LuaValue f = state.load(
			"local co = coroutine.create(function()\n" + body + "\nend)\n" +
			"return coroutine.resume(co)", "boundary");
		return f.invoke(LuaValue.NONE);
	}

	private void assertBoundaryError(Varargs r) {
		assertFalse("resume should fail: " + r, r.arg1().toboolean());
		assertTrue("got: " + r.arg(2), r.arg(2).tojstring().contains("C-call boundary"));
	}

	public void testYieldInsideTableSortComparatorIsAnError() {
		assertBoundaryError(resumeOnce(
			"table.sort({3, 1, 2}, function(a, b) coroutine.yield('y') return a < b end)"));
	}

	public void testYieldInsideGsubCallbackIsAnError() {
		assertBoundaryError(resumeOnce(
			"return string.gsub('abc', '%w', function(c) coroutine.yield(c) return c:upper() end)"));
	}

	/** The supported shape still works: yielding Lua function called directly from the coroutine. */
	public void testYieldFromCoroutineBodyStillWorks() {
		Varargs r = resumeOnce("coroutine.yield('ok')");
		assertTrue(r.arg1().toboolean());
		assertEquals("ok", r.arg(2).tojstring());
	}

	/** Runs {@code body} in a coroutine; returns resume ok, pcall ok, err, status, reached. */
	private Varargs pcallInCoroutine(String pcallExpr) {
		state.globals.set("yield_fn", new YieldAndReturnFunction(state));
		LuaValue f = state.load(
			"reached = nil\n" +
			"local co = coroutine.create(function()\n" +
			"  local ok, err = " + pcallExpr + "\n" +
			"  reached = true\n" +
			"  return ok, err\n" +
			"end)\n" +
			"local r, ok, err = coroutine.resume(co)\n" +
			"return r, ok, err, coroutine.status(co), reached", "pcallbarrier");
		return f.invoke(LuaValue.NONE);
	}

	private void assertPcallBoundaryError(Varargs r) {
		assertTrue("resume itself must succeed: " + r, r.arg(1).toboolean());
		assertFalse("pcall must report failure: " + r, r.arg(2).toboolean());
		assertTrue("got: " + r.arg(3), r.arg(3).tojstring().contains("C-call boundary"));
		assertEquals("dead", r.arg(4).tojstring());
	}

	/** pcall is a protected frame, so a yield inside the pcall'd closure suspends and resumes. */
	public void testYieldInsidePcallClosureSuspendsAndResumes() {
		state.globals.set("reached_in_pcall", LuaValue.NIL);
		LuaValue f = state.load(
			"local co = coroutine.create(function()\n" +
			"  return pcall(function() coroutine.yield('y'); reached_in_pcall = true end)\n" +
			"end)\n" +
			"local r1, y = coroutine.resume(co)\n" +
			"local s1 = coroutine.status(co)\n" +
			"local r2, ok = coroutine.resume(co)\n" +
			"return r1, y, s1, r2, ok, coroutine.status(co), reached_in_pcall", "pcallclosure");
		Varargs r = f.invoke(LuaValue.NONE);
		assertTrue(r.arg(1).toboolean());
		assertEquals("y", r.arg(2).tojstring());
		assertEquals("suspended", r.arg(3).tojstring());
		assertTrue(r.arg(4).toboolean());
		assertTrue(r.arg(5).toboolean());
		assertEquals("dead", r.arg(6).tojstring());
		assertTrue(r.arg(7).toboolean());
	}

	public void testYieldInsideSortComparatorUnderPcallIsAnError() {
		Varargs r = pcallInCoroutine(
			"pcall(table.sort, {3, 1, 2}, function(a, b) coroutine.yield('y') return a < b end)");
		assertPcallBoundaryError(r);
	}

	public void testYieldInsideLuaMetamethodIsAnError() {
		Varargs r = pcallInCoroutine(
			"pcall(function()\n" +
			"  local t = setmetatable({}, {__index = function(_, k) coroutine.yield(k) return 1 end})\n" +
			"  return t.x\n" +
			"end)");
		assertPcallBoundaryError(r);
	}

	public void testPcallOfContinuableFunctionStillSuspendsAndResumes() {
		state.globals.set("yield_fn", new YieldAndReturnFunction(state));
		LuaValue f = state.load(
			"local co = coroutine.create(function()\n" +
			"  return pcall(yield_fn, 'q')\n" +
			"end)\n" +
			"local r1, y = coroutine.resume(co)\n" +
			"local r2, ok, v = coroutine.resume(co, 'back')\n" +
			"return r1, y, r2, ok, v, coroutine.status(co)", "pcallok");
		Varargs r = f.invoke(LuaValue.NONE);
		assertTrue(r.arg(1).toboolean());
		assertEquals("q", r.arg(2).tojstring());
		assertTrue(r.arg(3).toboolean());
		assertTrue(r.arg(4).toboolean());
		assertEquals("dead", r.arg(6).tojstring());
	}

	/**
	 * A tail call out of the pcall'd closure runs after the closure's activation has ended, so the
	 * yield is at nest depth 0 and suspends exactly like pcall(continuableFn).
	 */
	public void testYieldInTailCallInsidePcallClosureSuspendsAndResumes() {
		state.globals.set("yield_fn", new YieldAndReturnFunction(state));
		LuaValue f = state.load(
			"local co = coroutine.create(function()\n" +
			"  return pcall(function() return yield_fn('q') end)\n" +
			"end)\n" +
			"local r1, y = coroutine.resume(co)\n" +
			"local st = coroutine.status(co)\n" +
			"local r2, ok = coroutine.resume(co, 'back')\n" +
			"return r1, y, st, r2, ok, coroutine.status(co)", "pcalltail");
		Varargs r = f.invoke(LuaValue.NONE);
		assertTrue(r.arg(1).toboolean());
		assertEquals("q", r.arg(2).tojstring());
		assertEquals("suspended", r.arg(3).tojstring());
		assertTrue(r.arg(4).toboolean());
		assertTrue(r.arg(5).toboolean());
		assertEquals("dead", r.arg(6).tojstring());
	}
}
