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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 
 * Subclass of {@link LuaValue} that implements 
 * a lua coroutine thread using Java Threads.
 * <p>
 * A LuaThread is typically created in response to a scripted call to 
 * {@code coroutine.create()}
 * <p>
 * The threads must be initialized with the {@link LuaState}, so that 
 * the global environment may be passed along according to rules of lua. 
 * This is done via the constructor arguments {@link #LuaThread(LuaState)} or 
 * {@link #LuaThread(LuaState, LuaValue)}.
 * <p> 
 * The utility class {@link org.luaj.vm2.lib.jse.JsePlatform} 
 * sees to it that this {@link LuaState} is initialized properly.
 * <p>
 * The behavior of coroutine threads matches closely the behavior 
 * of C coroutine library.  However, because of the use of Java threads 
 * to manage call state, it is possible to yield from anywhere in luaj. 
 * <p>
 * Each Java thread wakes up at regular intervals and checks a weak reference
 * to determine if it can ever be resumed.  If not, it throws 
 * {@link OrphanedThread} which is an {@link java.lang.Error}. 
 * Applications should not catch {@link OrphanedThread}, because it can break
 * the thread safety of luaj.  The value controlling the polling interval 
 * is {@link #thread_orphan_check_interval} and may be set by the user.
 * <p> 
 * There are two main ways to abandon a coroutine.  The first is to call 
 * {@code yield()} from lua, or equivalently {@link Globals#yield(Varargs)}, 
 * and arrange to have it never resumed possibly by values passed to yield.
 * The second is to throw {@link OrphanedThread}, which should put the thread
 * in a dead state.   In either case all references to the thread must be
 * dropped, and the garbage collector must run for the thread to be 
 * garbage collected. 
 * <p>
 * Coroutines use Java virtual threads by default (Java 21+), which allows
 * millions of concurrent coroutines with minimal memory overhead. Platform
 * threads can be used instead by setting {@link LuaState#coroutineThreadFactory}
 * to {@link #PLATFORM_THREAD_FACTORY}.
 *

 * @see LuaValue
 * @see org.luaj.vm2.lib.jse.JsePlatform
 * @see org.luaj.vm2.lib.CoroutineLib
 */
public class LuaThread extends LuaValue {

	/** Shared metatable for lua threads. */
	public static LuaValue s_metatable;

	private static final AtomicLong coroutineCounter = new AtomicLong(0);

	@FunctionalInterface
	public interface ThreadFactory {
		Thread newThread(Runnable target, String name);
	}

	public static final ThreadFactory VIRTUAL_THREAD_FACTORY =
		(target, name) -> Thread.ofVirtual().name(name).unstarted(target);

	public static final ThreadFactory PLATFORM_THREAD_FACTORY =
		(target, name) -> Thread.ofPlatform().name(name).unstarted(target);

	/** Polling interval, in milliseconds, which each thread uses while waiting to
	 * return from a yielded state to check if the lua threads is no longer
	 * referenced and therefore should be garbage collected.  
	 * A short polling interval for many threads will consume server resources. 
	 * Orphaned threads cannot be detected and collected unless garbage
	 * collection is run.  This can be changed by Java startup code if desired.
	 */
	public static long thread_orphan_check_interval = 5000;
	
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

	/** Whether this thread runs synchronously on the calling thread.
	 * May be changed to support async (virtual thread) mode in the future. */
	public final boolean isSync;

	/** Private constructor for main thread only */
	public LuaThread(LuaState state) {
		threadState = new State(state, this, null);
		threadState.status = STATUS_RUNNING;
		this.state = state;
		this.isSync = false;
	}

	/**
	 * Create a LuaThread around a function and environment.
	 * Always runs synchronously on the calling thread.
	 * @param func The function to execute
	 */
	public LuaThread(LuaState state, LuaValue func) {
		LuaValue.assert_(func != null, "function cannot be null");
		threadState = new State(state, this, func);
		this.state = state;
		this.isSync = true; // may support async in future
		inheritHook();
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
		final LuaThread.State s = this.threadState;
		if (s.status > LuaThread.STATUS_SUSPENDED)
			return LuaValue.varargsOf(LuaValue.FALSE,
					LuaValue.valueOf("cannot resume "+(s.status==LuaThread.STATUS_DEAD? "dead": "non-suspended")+" coroutine"));
		return s.lua_resume_sync(this, args);
	}

	public static class State implements Runnable {
		final LuaState state;
		final WeakReference<LuaThread> lua_thread;
		public final LuaValue function;
		private ReentrantLock lock;
		private Condition condition;

		private ReentrantLock getLock() {
			if (lock == null) {
				lock = new ReentrantLock();
				condition = lock.newCondition();
			}
			return lock;
		}

		private Condition getCondition() {
			getLock();
			return condition;
		}
		Varargs args = LuaValue.NONE;
		Varargs result = LuaValue.NONE;
		String error = null;

		Deque<LuaFrame> frameStack = new ArrayDeque<>();
		LuaValue yieldSentinel;
		Varargs resumeArgs = LuaValue.NONE;
		boolean yieldRequested;
		boolean yieldIsInterrupt;

		/** Depth of sync-compiled (nova.sync) calls on this thread.
		 *  Non-zero means yielding is prohibited. */
		int syncCompiledDepth;

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
		
		public void run() {
			LuaState.setCurrent(state);
			getLock().lock();
			try {
				try {
					Varargs a = this.args;
					this.args = LuaValue.NONE;
					this.result = function.invoke(a);
				} catch (Throwable t) {
					this.error = t.getMessage();
				} finally {
					this.status = LuaThread.STATUS_DEAD;
					getCondition().signal();
				}
			} finally {
				getLock().unlock();
			}
		}

		public Varargs lua_resume(LuaThread new_thread, Varargs args) {
			getLock().lock();
			try {
				LuaThread previous_thread = state.currentThread;
				try {
					state.currentThread = new_thread;
					this.args = args;
					if (this.status == STATUS_INITIAL) {
						this.status = STATUS_RUNNING;
						ThreadFactory factory = state.coroutineThreadFactory != null
							? state.coroutineThreadFactory
							: VIRTUAL_THREAD_FACTORY;
						Thread t = factory.newThread(this, "Coroutine-" + coroutineCounter.incrementAndGet());
						t.start();
					} else {
						getCondition().signal();
					}
					if (previous_thread != null)
						previous_thread.threadState.status = STATUS_NORMAL;
					this.status = STATUS_RUNNING;
					getCondition().await();
					return (this.error != null? 
						LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(this.error)):
						LuaValue.varargsOf(LuaValue.TRUE, this.result));
				} catch (InterruptedException ie) {
					throw new OrphanedThread();
				} finally {
					this.args = LuaValue.NONE;
					this.result = LuaValue.NONE;
					this.error = null;
					state.currentThread = previous_thread;
					if (previous_thread != null)
						state.currentThread = previous_thread;
				}
			} finally {
				getLock().unlock();
			}
		}

		public Varargs lua_yield(Varargs args) {
			if (syncCompiledDepth > 0)
				throw new LuaError("attempt to yield across a sync-compiled boundary");
			getLock().lock();
			try {
				try {
					this.result = args;
					this.status = STATUS_SUSPENDED;
					getCondition().signal();
					do {
						getCondition().await(thread_orphan_check_interval, TimeUnit.MILLISECONDS);
						if (this.lua_thread.get() == null) {
							this.status = STATUS_DEAD;
							throw new OrphanedThread();
						}
					} while (this.status == STATUS_SUSPENDED);
					return this.args;
				} catch (InterruptedException ie) {
					this.status = STATUS_DEAD;
					throw new OrphanedThread();
				} finally {
					this.args = LuaValue.NONE;
					this.result = LuaValue.NONE;
				}
			} finally {
				getLock().unlock();
			}
		}

		public Varargs lua_yield_sync(Varargs args) {
			if (syncCompiledDepth > 0)
				throw new LuaError("attempt to yield across a sync-compiled boundary");
			this.result = args;
			this.status = STATUS_SUSPENDED;
			this.yieldRequested = true;
			return LuaValue.NONE;
		}

		public Varargs lua_resume_sync(LuaThread new_thread, Varargs args) {
			LuaThread previous_thread = state.currentThread;
			try {
				state.currentThread = new_thread;
				if (previous_thread != null)
					previous_thread.threadState.status = STATUS_NORMAL;
				this.status = STATUS_RUNNING;

				if (yieldSentinel == null) {
					LuaValue coroutine = state.globals.get("coroutine");
					if (!coroutine.isnil())
						yieldSentinel = coroutine.get("yield");
				}

				if (frameStack.isEmpty()) {
					if (this.function instanceof LuaClosure lc) {
						LuaValue[] stack = new LuaValue[lc.p.maxstacksize];
						System.arraycopy(LuaValue.NILS, 0, stack, 0, lc.p.maxstacksize);
						for (int i = 0; i < lc.p.numparams; i++)
							stack[i] = args.arg(i + 1);
						Varargs varargs = lc.p.is_vararg != 0 ? args.subargs(lc.p.numparams + 1) : LuaValue.NONE;
						LuaFrame frame = new LuaFrame();
						frame.closure = lc;
						frame.stack = stack;
						frame.varargs = varargs;
						frame.v = LuaValue.NONE;
						frame.openups = lc.p.p.length > 0 ? new UpValue[stack.length] : null;
						frame.callerOp = 0;
						frame.callerA = 0;
						frame.callerB = 0;
						frame.callerC = 0;
						frameStack.push(frame);
					} else {
						throw new LuaError("cannot resume non-LuaClosure coroutine");
					}
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
					this.status = STATUS_DEAD;
					frameStack.clear();
					return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(this.error));
				} catch (Throwable t) {
					this.error = t.getMessage();
					this.status = STATUS_DEAD;
					frameStack.clear();
					return LuaValue.varargsOf(LuaValue.FALSE,
						LuaValue.valueOf(this.error != null ? this.error : t.toString()));
				} finally {
					state.javaCallDepth = savedJavaCallDepth;
				}
			} finally {
				state.currentThread = previous_thread;
				if (previous_thread != null)
					state.currentThread = previous_thread;
				this.args = LuaValue.NONE;
				this.result = LuaValue.NONE;
				this.error = null;
				this.resumeArgs = LuaValue.NONE;
			}
		}
	}
		
}
