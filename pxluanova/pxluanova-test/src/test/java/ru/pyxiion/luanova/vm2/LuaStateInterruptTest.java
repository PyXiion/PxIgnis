package ru.pyxiion.luanova.vm2;

import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.compiler.LuaC;
import ru.pyxiion.luanova.vm2.interrupt.InterruptAction;
import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform;

public class LuaStateInterruptTest extends TestCase {

	public void testInterruptWithoutHandlerThrows() {
		LuaState state = LuaState.builder().build();
		try {
			state.interrupt();
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
		}
	}

	public void testInterruptContinueCallsHandler() throws Exception {
		AtomicBoolean handlerCalled = new AtomicBoolean(false);
		LuaState state = LuaState.builder()
			.interruptHandler(() -> {
				handlerCalled.set(true);
				throw new LuaError("interrupted");
			})
			.build();
		LoadState.install(state);
		LuaC.install(state);

		LuaValue chunk = state.load("while true do end", "test");

		Thread t = Thread.ofVirtual().start(() -> {
			try {
				chunk.call();
			} catch (LuaError e) {
			}
		});

		Thread.sleep(100);
		state.interrupt();
		t.join(5000);

		assertTrue("Interrupt handler was not called", handlerCalled.get());
		assertFalse(t.isAlive());
	}

	public void testInterruptSuspendYieldsCoroutine() throws Exception {
		AtomicBoolean handlerCalled = new AtomicBoolean(false);
		LuaState state = LuaState.builder()
			.interruptHandler(() -> {
				handlerCalled.set(true);
				return InterruptAction.SUSPEND;
			})
			.build();
		LoadState.install(state);
		LuaC.install(state);

		LuaValue func = state.load("while true do end", "test");
		LuaThread thread = new LuaThread(state, func);

		Thread t = Thread.ofVirtual().start(() -> thread.resume(LuaValue.NONE));
		Thread.sleep(100);

		state.interrupt();
		Thread.sleep(200);

		assertTrue("Interrupt handler was not called", handlerCalled.get());
		assertEquals("suspended", thread.getStatus());
	}
}
