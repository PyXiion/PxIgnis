package org.luaj.vm2;

import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.interrupt.InterruptAction;

public class LuaStateCheckpointTest extends TestCase {

	private static LuaState newState(LuaState.Builder builder) {
		LuaState state = builder.build();
		LoadState.install(state);
		LuaC.install(state);
		return state;
	}

	/** Handler that aborts after {@code limit} polls. */
	private static LuaState abortingState(AtomicInteger polls, int limit) {
		return newState(LuaState.builder().checkpointHandler(() -> {
			if (polls.incrementAndGet() >= limit) throw new LuaError("checkpoint limit");
			return InterruptAction.CONTINUE;
		}));
	}

	public void testHandlerIsPolledOncePerInterval() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = newState(LuaState.builder()
			.checkpointInterval(10)
			.checkpointHandler(() -> {
				polls.incrementAndGet();
				return InterruptAction.CONTINUE;
			}));

		state.load("local x = 0 for i = 1, 1000 do x = x + i end", "test").call();

		// ~3 instructions per iteration, so a few hundred polls - but nowhere near one per instruction.
		assertTrue("too few polls: " + polls.get(), polls.get() > 100);
		assertTrue("too many polls: " + polls.get(), polls.get() < 1000);
	}

	public void testHandlerErrorStopsInfiniteLoopInDirectCall() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = abortingState(polls, 50);
		try {
			state.load("while true do end", "test").call();
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("checkpoint limit"));
		}
	}

	public void testHandlerErrorStopsInfiniteLoopInCoroutine() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = abortingState(polls, 50);
		LuaThread thread = new LuaThread(state, state.load("while true do end", "test"));

		Varargs result = thread.resume(LuaValue.NONE);

		assertFalse(result.arg1().toboolean());
		assertTrue(result.arg(2).tojstring(), result.arg(2).tojstring().contains("checkpoint limit"));
	}

	public void testHandlerErrorStopsInfiniteTailRecursion() {
		// Each call runs only a couple of instructions; the countdown must carry across calls.
		AtomicInteger polls = new AtomicInteger();
		LuaState state = abortingState(polls, 50);
		try {
			state.load("local function f() return f() end return f()", "test").call();
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("checkpoint limit"));
		}
	}

	public void testPcallCannotSwallowRepeatedRejection() {
		// Once tripped, the handler rejects every poll; the outer loop's own instructions must be polled too.
		AtomicInteger polls = new AtomicInteger();
		LuaState state = abortingState(polls, 50);
		state.globals.load(new org.luaj.vm2.lib.BaseLib());
		try {
			state.load("while true do pcall(function() while true do end end) end", "test").call();
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("checkpoint limit"));
		}
	}

	public void testContinueRestoresNormalInterval() {
		// Rejects exactly once, then continues: polling must go back to once per interval.
		AtomicInteger polls = new AtomicInteger();
		LuaState state = newState(LuaState.builder()
			.checkpointInterval(10)
			.checkpointHandler(() -> {
				if (polls.incrementAndGet() == 1) throw new LuaError("once");
				return InterruptAction.CONTINUE;
			}));
		state.globals.load(new org.luaj.vm2.lib.BaseLib());

		state.load("pcall(function() while true do end end) local x = 0 for i = 1, 1000 do x = x + i end", "test").call();

		assertTrue("too many polls: " + polls.get(), polls.get() < 1000);
	}

	/** nova.sync / LuaJC code has no interpreter loop: it polls on backward jumps and tail-call loops. */
	private static LuaState compilingState(AtomicInteger polls, int limit) {
		LuaState state = abortingState(polls, limit);
		state.globals.load(new org.luaj.vm2.lib.BaseLib());
		state.globals.load(new org.luaj.vm2.lib.jse.NovaLib());
		return state;
	}

	private static void assertStopped(LuaState state, String script) {
		try {
			state.load(script, "test").call();
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("checkpoint limit"));
		}
	}

	public void testCompiledInfiniteLoopIsStopped() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = compilingState(polls, 50);
		assertStopped(state, "nova.sync(function() while true do end end)()");
	}

	public void testCompiledNumericForIsPolled() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = compilingState(polls, 50);
		assertStopped(state, "nova.sync(function() local x = 0 for i = 1, 1e12 do x = x + i end return x end)()");
	}

	public void testCompiledPcallLoopIsStopped() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = compilingState(polls, 50);
		assertStopped(state, "nova.sync(function() while true do pcall(function() while true do end end) end end)()");
	}

	public void testCompiledTailRecursionIsStopped() {
		AtomicInteger polls = new AtomicInteger();
		LuaState state = compilingState(polls, 50);
		assertStopped(state, "nova.sync(function() local function f(n) return f(n + 1) end return f(0) end)()");
	}

	public void testSuspendYieldsCoroutine() {
		LuaState state = newState(LuaState.builder().checkpointHandler(() -> InterruptAction.SUSPEND));
		LuaThread thread = new LuaThread(state, state.load("while true do end", "test"));

		thread.resume(LuaValue.NONE);

		assertEquals("suspended", thread.getStatus());
	}

	public void testNoHandlerRunsNormally() {
		LuaState state = newState(LuaState.builder());
		LuaValue r = state.load("local x = 0 for i = 1, 1000 do x = x + i end return x", "test").call();
		assertEquals(500500, r.toint());
	}

	public void testInvalidIntervalRejected() {
		try {
			LuaState.builder().checkpointInterval(0);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException e) {
		}
	}
}
