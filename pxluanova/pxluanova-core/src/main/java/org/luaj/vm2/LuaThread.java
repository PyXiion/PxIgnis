/*******************************************************************************
* Copyright (c) 2007-2012 LuaJ. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package org.luaj.vm2;


import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A Lua coroutine.
 * <p>
 * Coroutines run on the thread that resumes them: {@link #resume(Varargs)} drives the frame interpreter
 * ({@link FrameInterpreter}) until the body returns, errors or yields. A yield saves the frame stack in
 * {@link State} and returns to the resumer; no Java thread is parked.
 * <p>
 * Lua code can yield from anywhere the frame interpreter runs it directly, including inside pcall/xpcall
 * and generic-for iterators. Lua code entered from a Java function (a metamethod, a table.sort comparator,
 * a load reader, ...) runs in a nested interpreter and cannot yield: that raises
 * "attempt to yield across a C-call boundary", as in Lua 5.2.
 *
 * @see LuaValue
 * @see org.luaj.vm2.lib.CoroutineLib
 */
public class LuaThread extends LuaValue {

	/** Shared metatable for lua threads. */
	public static LuaValue s_metatable;

	/** Callback used to resume a coroutine after an asynchronous operation completes.
	 * The runtime sets this on the main thread; child coroutines inherit it. */
	@FunctionalInterface
	public interface ResumeHandler {
		void resume(LuaThread thread, Varargs args);
	}

	public static final int STATUS_INITIAL       = 0;
	public static final int STATUS_SUSPENDED     = 1;
	public static final int STATUS_RUNNING       = 2;
	public static final int STATUS_NORMAL        = 3;
	public static final int STATUS_DEAD          = 4;
	public static final String[] STATUS_NAMES = { 
		"suspended", 
		"suspended", 
		"running", 
		"normal", 
		"dead",};
	
	public final State threadState;

	public static final int        MAX_CALLSTACK = 256;

	/** Thread-local used by DebugLib to store debugging state. 
	 * This is an opaque value that should not be modified by applications. */
	public Object callstack;

	public final LuaState state;

	/** Error message handler for this thread, if any.  */
	public LuaValue errorfunc;

	/** Callback used to resume this coroutine after an asynchronous operation
	 * completes. Inherited from the parent (or main) thread at construction time. */
	public volatile ResumeHandler resumeHandler;

	/** Generic execution context for this thread. Opaque to the core runtime;
	 * the host application may store any object here (e.g. an executor).
	 * Inherited from the parent (or main) thread at construction time. */
	public volatile Object executionContext;

	Throwable lastError = null;

	/** Private constructor for main thread only */
	public LuaThread(LuaState state) {
		threadState = new State(state, this, null);
		threadState.status = STATUS_RUNNING;
		this.state = state;
	}

	/**
	 * Create a coroutine around a function.
	 * @param func The function to execute
	 */
	public LuaThread(LuaState state, LuaValue func) {
		LuaValue.assert_(func != null, "function cannot be null");
		threadState = new State(state, this, func);
		this.state = state;
		this.resumeHandler = resolveResumeHandler(state);
		this.executionContext = resolveExecutionContext(state);
		inheritHook();
	}

	private ResumeHandler resolveResumeHandler(LuaState state) {
		LuaThread parent = state.getCurrentThread();
		if (parent != null && parent.resumeHandler != null)
			return parent.resumeHandler;
		LuaThread main = state.getMainThread();
		if (main != null && main.resumeHandler != null)
			return main.resumeHandler;
		return null;
	}

	private Object resolveExecutionContext(LuaState state) {
		LuaThread parent = state.getCurrentThread();
		if (parent != null && parent.executionContext != null)
			return parent.executionContext;
		LuaThread main = state.getMainThread();
		if (main != null && main.executionContext != null)
			return main.executionContext;
		return null;
	}

	private void inheritHook() {
		LuaThread parent = state.getCurrentThread();
		if (parent != null && parent.threadState != null) {
			State ps = parent.threadState;
			if (ps.hookfunc != null) {
				threadState.hookfunc = ps.hookfunc;
				threadState.hookcall = ps.hookcall;
				threadState.hookline = ps.hookline;
				threadState.hookrtrn = ps.hookrtrn;
				threadState.hookcount = ps.hookcount;
			}
		}
	}

	public Throwable getLastError() {
		return lastError;
	}
	
	public int type() {
		return LuaValue.TTHREAD;
	}
	
	public String typename() {
		return "thread";
	}
	
	public boolean isthread() {
		return true;
	}
	
	public LuaThread optthread(LuaThread defval) {
		return this;
	}
	
	public LuaThread checkthread() {
		return this;
	}
	
	public LuaValue getmetatable() { 
		return s_metatable; 
	}
	
	public String getStatus() {
		return STATUS_NAMES[threadState.status];
	}

	public boolean isMainThread() {
		return this.threadState.function == null;
	}

	public Varargs resume(Varargs args) {
		lastError = null;
		final LuaThread.State s = this.threadState;
		if (s.status > LuaThread.STATUS_SUSPENDED)
			return LuaValue.varargsOf(LuaValue.FALSE,
					LuaValue.valueOf("cannot resume "+(s.status==LuaThread.STATUS_DEAD? "dead": "non-suspended")+" coroutine"));
		return s.lua_resume_sync(this, args);
	}

	public static class State {
		final LuaState state;
		final WeakReference<LuaThread> lua_thread;
		public final LuaValue function;
		public Varargs result = LuaValue.NONE;
		String error = null;

		Deque<LuaFrame> frameStack = new ArrayDeque<>();
		LuaValue yieldSentinel;
		Varargs resumeArgs = LuaValue.NONE;
		public boolean yieldRequested;
		public boolean yieldIsInterrupt;

		public boolean isYieldPending() {
			return yieldRequested && !yieldIsInterrupt;
		}

		/** Depth of sync-compiled (nova.sync) calls on this thread.
		 *  Non-zero means yielding is prohibited. */
		int syncCompiledDepth;

		/** Depth of Lua code entered from Java on this coroutine (nested {@link LuaClosure#execute} calls).
		 *  Such code has no saved frames to resume, so non-zero means yielding is prohibited. */
		int nonYieldableDepth;

		/**
		 * Marks the start of Lua code entered from Java on the current coroutine, if any.
		 * Returns the state whose {@code nonYieldableDepth} the caller must decrement when done, or null.
		 */
		static State enterNonYieldable(LuaState state) {
			if (state == null)
				return null;
			LuaThread ct = state.getCurrentThread();
			if (ct == null || ct.isMainThread())
				return null;
			ct.threadState.nonYieldableDepth++;
			return ct.threadState;
		}

		/** Whether a yield is allowed right now. */
		public boolean isYieldable() {
			return syncCompiledDepth == 0 && nonYieldableDepth == 0;
		}

		/** Hook function control state used by debug lib. */
		public LuaValue hookfunc;

		public boolean hookline;
		public boolean hookcall;
		public boolean hookrtrn;
		public int hookcount;
		public boolean inhook;
		public int lastline;
		public int bytecodes;
		
		public int status = LuaThread.STATUS_INITIAL;

		State(LuaState state, LuaThread lua_thread, LuaValue function) {
			this.state = state;
			this.lua_thread = new WeakReference<>(lua_thread);
			this.function = function;
		}

		private void setLastError(Throwable e) {
			var luaThread = lua_thread.get();
			if (luaThread != null) {
				luaThread.lastError = e;
			}
		}
		
		public Varargs lua_yield_sync(Varargs args) {
			if (syncCompiledDepth > 0)
				throw new LuaError("attempt to yield across a sync-compiled boundary");
			if (nonYieldableDepth > 0)
				throw new LuaError("attempt to yield across a C-call boundary");
			this.result = args;
			this.status = STATUS_SUSPENDED;
			this.yieldRequested = true;
			return LuaValue.NONE;
		}

		public Varargs lua_resume_sync(LuaThread new_thread, Varargs args) {
			LuaState previousState = LuaState.current();
			LuaThread previous_thread = state.getCurrentThread();
			try {
				LuaState.setCurrent(state);
				state.setCurrentThread(new_thread);
				if (previous_thread != null && previous_thread != new_thread
					&& previous_thread.threadState.status == STATUS_RUNNING)
					previous_thread.threadState.status = STATUS_NORMAL;
				this.status = STATUS_RUNNING;

				if (yieldSentinel == null) {
					LuaValue coroutine = state.globals.get("coroutine");
					if (!coroutine.isnil())
						yieldSentinel = coroutine.get("yield");
				}

				if (frameStack.isEmpty()) {
					// A Java function body runs through a trampoline frame, so it can yield like a Lua call.
					frameStack.push(this.function instanceof LuaClosure lc
						? FrameInterpreter.newFrame(lc, args)
						: FrameInterpreter.trampolineFrame(state, this.function, args));
				} else {
					this.resumeArgs = args;
				}

				int savedJavaCallDepth = state.javaCallDepth;
				state.javaCallDepth = 0;
				try {
					Varargs result = FrameInterpreter.run(this);
					if (this.status == STATUS_SUSPENDED) {
						return LuaValue.varargsOf(LuaValue.TRUE, result);
					}
					this.status = STATUS_DEAD;
					return LuaValue.varargsOf(LuaValue.TRUE, result);
				} catch (LuaError le) {
					this.error = le.getMessage();
					setLastError(le);
					this.status = STATUS_DEAD;
					frameStack.clear();
					return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(this.error));
				} catch (Throwable t) {
					this.error = t.getMessage();
					setLastError(t);
					this.status = STATUS_DEAD;
					frameStack.clear();
					return LuaValue.varargsOf(LuaValue.FALSE,
						LuaValue.valueOf(this.error != null ? this.error : t.toString()));
				} finally {
					state.javaCallDepth = savedJavaCallDepth;
				}
			} finally {
				state.setCurrentThread(previous_thread);
				LuaState.setCurrent(previousState);
				if (previous_thread != null && previous_thread != new_thread
					&& previous_thread.threadState.status == STATUS_NORMAL)
					previous_thread.threadState.status = STATUS_RUNNING;
				this.result = LuaValue.NONE;
				this.error = null;
				this.resumeArgs = LuaValue.NONE;
			}
		}
	}
		
}
