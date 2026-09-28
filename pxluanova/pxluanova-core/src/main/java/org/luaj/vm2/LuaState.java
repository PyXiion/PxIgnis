package org.luaj.vm2;

import org.luaj.vm2.interrupt.InterruptAction;
import org.luaj.vm2.interrupt.InterruptHandler;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.ResourceFinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.util.Objects;
import java.util.function.Supplier;

public final class LuaState {
	private static final ThreadLocal<LuaState> current = new ThreadLocal<>();

	public static LuaState current() {
		return current.get();
	}

	public static void setCurrent(LuaState state) {
		if (state == null) current.remove();
		else current.set(state);
	}

	public interface Loader {
		LuaFunction load(Prototype prototype, String chunkname, LuaValue env) throws IOException;
	}

	public interface Compiler {
		Prototype compile(InputStream stream, String chunkname) throws IOException;
	}

	public interface Undumper {
		Prototype undump(InputStream stream, String chunkname) throws IOException;
	}

	static class StrReader extends Reader {
		final String s;
		int i = 0;
		final int n;
		StrReader(String s) {
			this.s = s;
			n = s.length();
		}
		public void close() throws IOException {
			i = n;
		}
		public int read() throws IOException {
			return i < n ? s.charAt(i++) : -1;
		}
		public int read(char[] cbuf, int off, int len) throws IOException {
			int j = 0;
			for (; j < len && i < n; ++j, ++i)
				cbuf[off+j] = s.charAt(i);
			return j > 0 || len == 0 ? j : -1;
		}
	}

	abstract static class AbstractBufferedStream extends InputStream {
		protected byte[] b;
		protected int i = 0, j = 0;
		protected AbstractBufferedStream(int buflen) {
			this.b = new byte[buflen];
		}
		abstract protected int avail() throws IOException;
		public int read() throws IOException {
			int a = avail();
			return (a <= 0 ? -1 : 0xff & b[i++]);
		}
		public int read(byte[] b) throws IOException {
			return read(b, 0, b.length);
		}
		public int read(byte[] b, int i0, int n) throws IOException {
			int a = avail();
			if (a <= 0) return -1;
			final int n_read = Math.min(a, n);
			System.arraycopy(this.b,  i,  b,  i0,  n_read);
			i += n_read;
			return n_read;
		}
		public long skip(long n) throws IOException {
			final long k = Math.min(n, j - i);
			i += k;
			return k;
		}
		public int available() throws IOException {
			return j - i;
		}
	}

	static class UTF8Stream extends AbstractBufferedStream {
		private final char[] c = new char[32];
		private final Reader r;
		UTF8Stream(Reader r) {
			super(96);
			this.r = r;
		}
		protected int avail() throws IOException {
			if (i < j) return j - i;
			int n = r.read(c);
			if (n < 0)
				return -1;
			if (n == 0) {
				int u = r.read();
				if (u < 0)
					return -1;
				c[0] = (char) u;
				n = 1;
			}
			j = LuaString.encodeToUtf8(c, n, b, i = 0);
			return j;
		}
		public void close() throws IOException {
			r.close();
		}
	}

	static class BufferedStream extends AbstractBufferedStream {
		private final InputStream s;
		public BufferedStream(InputStream s) {
			this(128, s);
		}
		BufferedStream(int buflen, InputStream s) {
			super(buflen);
			this.s = s;
		}
		protected int avail() throws IOException {
			if (i < j) return j - i;
			if (j >= b.length) i = j = 0;
			int n = s.read(b, j, b.length - j);
			if (n < 0)
				return -1;
			if (n == 0) {
				int u = s.read();
				if (u < 0)
					return -1;
				b[j] = (byte) u;
				n = 1;
			}
			j += n;
			return n;
		}
		public void close() throws IOException {
			s.close();
		}
		public synchronized void mark(int n) {
			if (i > 0 || n > b.length) {
				byte[] dest = n > b.length ? new byte[n] : b;
				System.arraycopy(b, i, dest, 0, j - i);
				j -= i;
				i = 0;
				b = dest;
			}
		}
		public boolean markSupported() {
			return true;
		}
		public synchronized void reset() throws IOException {
			i = 0;
		}
	}

	public LuaTable stringMetatable;
	public LuaTable booleanMetatable;
	public LuaTable numberMetatable;
	public LuaTable nilMetatable;
	public LuaTable functionMetatable;
	public LuaTable threadMetatable;

	public Compiler compiler;
	public Loader loader;
	public Undumper undumper;

	/** Default number of VM instructions between {@link #checkpoint()} polls. */
	public static final int DEFAULT_CHECKPOINT_INTERVAL = 100;

	private volatile boolean interrupted;
	private final InterruptHandler interruptHandler;
	private final InterruptHandler checkpointHandler;
	private final int checkpointInterval;

	/** Countdown for LuaJC-compiled code, which has no LuaState at hand; see {@link #compiledBackwardJump()}. */
	private static int compiledCountdown = DEFAULT_CHECKPOINT_INTERVAL;

	/**
	 * Called by LuaJC-compiled code on every backward jump (loop iteration) and by tail-call loops: runs
	 * {@link #checkpoint()} on the current state once every {@link #DEFAULT_CHECKPOINT_INTERVAL} calls.
	 * Unsynchronized on purpose, like {@link #checkpointCountdown}.
	 */
	public static void compiledBackwardJump() throws LuaError {
		if (--compiledCountdown > 0)
			return;
		compiledCountdown = DEFAULT_CHECKPOINT_INTERVAL;
		LuaState s = current();
		if (s == null)
			return;
		try {
			s.checkpoint();
		} catch (LuaError e) {
			compiledCountdown = 1; // as in checkpoint(): re-check at the very next jump, so pcall cannot hide it
			throw e;
		}
	}

	/** Instructions left until the next checkpoint. Shared by every thread running this state;
	 *  it is deliberately not synchronized: a lost decrement only delays a checkpoint slightly. */
	int checkpointCountdown;

	int javaCallDepth = 0;

	private final ThreadLocal<LuaThread> currentThread = new ThreadLocal<>();
	private final LuaThread mainThread;

	public final LuaTable globals;

	private final ErrorReporter reportError;

	private final GlobalRegistry registry = new GlobalRegistry();

	public ResourceFinder finder;

	public InputStream STDIN;
	public PrintStream STDOUT = System.out;
	public PrintStream STDERR = System.err;

	public BaseLib baselib;
	public PackageLib package_;
	public DebugLib debuglib;

	public LuaState() {
		this(new Builder());
	}

	private LuaState(Builder builder) {
		compiler = builder.compiler;
		interruptHandler = builder.interruptHandler;
		checkpointHandler = builder.checkpointHandler;
		checkpointInterval = builder.checkpointInterval;
		checkpointCountdown = checkpointInterval;
		reportError = builder.reportError;

		globals = new LuaTable();
		globals.set("_G", globals);
		globals.set("_VERSION", Lua._VERSION);
		mainThread = new LuaThread(this);
		currentThread.set(mainThread);
		setCurrent(this);
	}

	public GlobalRegistry registry() {
		return registry;
	}

	public LuaThread getMainThread() {
		return mainThread;
	}

	public LuaThread getCurrentThread() {
		return currentThread.get();
	}

	void setCurrentThread(LuaThread thread) {
		if (thread == null) currentThread.remove();
		else currentThread.set(thread);
	}

	public void interrupt() {
		if (interruptHandler == null) throw new IllegalStateException("LuaState has no interrupt handler");
		interrupted = true;
	}

	public boolean isInterrupted() {
		return interrupted;
	}

	public void handleInterrupt() throws LuaError {
		interrupted = false;
		apply(interruptHandler.interrupted());
	}

	/**
	 * Called by the interpreters once every {@code checkpointInterval} instructions instead of on each one.
	 * Services a pending {@link #interrupt()} and polls the checkpoint handler, if any.
	 */
	void checkpoint() throws LuaError {
		checkpointCountdown = checkpointInterval;
		if (interrupted)
			handleInterrupt();
		if (checkpointHandler != null) {
			InterruptAction action;
			try {
				action = checkpointHandler.interrupted();
			} catch (LuaError e) {
				// Poll again on the very next instruction: if a pcall catches this error, the handler gets
				// to reject the code around it too, instead of that code running for another full interval.
				checkpointCountdown = 1;
				throw e;
			}
			apply(action);
		}
	}

	private void apply(InterruptAction action) throws LuaError {
		switch (action) {
			case CONTINUE -> {}
			case SUSPEND -> {
			LuaThread ct = getCurrentThread();
			if (ct == null || ct.threadState.status != LuaThread.STATUS_RUNNING) {
				throw new IllegalStateException("Cannot suspend non-running coroutine");
			}
			if (ct.isMainThread())
				throw new LuaError("cannot yield main thread");
			if (!ct.threadState.isYieldable())
				return; // inside Java-entered or sync-compiled code: keep running, suspend at a later checkpoint
			ct.threadState.yieldIsInterrupt = true;
			ct.threadState.lua_yield_sync(LuaValue.NONE);
			}
		}
	}

	public void handleInterruptWithoutYield() throws LuaError {
		interrupted = false;
		switch (interruptHandler.interrupted()) {
			case CONTINUE -> {}
			case SUSPEND -> interrupted = true;
		}
	}

	public void enteringJavaCall() {
		javaCallDepth++;
	}

	public void leavingJavaCall() {
		javaCallDepth--;
	}

	public void enterSyncCompiled() {
		LuaThread ct = getCurrentThread();
		if (ct != null && !ct.isMainThread()) {
			ct.threadState.syncCompiledDepth++;
		}
	}

	public void leaveSyncCompiled() {
		LuaThread ct = getCurrentThread();
		if (ct != null && !ct.isMainThread()) {
			ct.threadState.syncCompiledDepth--;
		}
	}

	public boolean isInJavaCall() {
		return javaCallDepth > 0;
	}

	public void reportInternalError(Throwable error, Supplier<String> message) {
		if (reportError != null) reportError.report(error, message);
	}

	public LuaValue loadfile(String filename) {
		return load(finder.findResource(filename), "@"+filename, "bt", globals);
	}

	public LuaValue load(String script, String chunkname) {
		return load(new StrReader(script), chunkname);
	}

	public LuaValue load(String script) {
		return load(new StrReader(script), script);
	}

	public LuaValue load(String script, String chunkname, LuaTable environment) {
		return load(new StrReader(script), chunkname, environment);
	}

	public LuaValue load(Reader reader, String chunkname) {
		return load(new UTF8Stream(reader), chunkname, "t", globals);
	}

	public LuaValue load(Reader reader, String chunkname, LuaTable environment) {
		return load(new UTF8Stream(reader), chunkname, "t", environment);
	}

	public LuaValue load(InputStream is, String chunkname, String mode, LuaValue environment) {
		try {
			Prototype p = loadPrototype(is, chunkname, mode);
			return loader.load(p, chunkname, environment);
		} catch (LuaError l) {
			throw l;
		} catch (Exception e) {
			return LuaValue.error("load "+chunkname+": "+e);
		}
	}

	public Prototype loadPrototype(InputStream is, String chunkname, String mode) throws IOException {
		if (mode.indexOf('b') >= 0) {
			if (undumper == null)
				LuaValue.error("No undumper.");
			if (!is.markSupported())
				is = new BufferedStream(is);
			is.mark(4);
			final Prototype p = undumper.undump(is, chunkname);
			if (p != null)
				return p;
			is.reset();
		}
		if (mode.indexOf('t') >= 0) {
			return compilePrototype(is, chunkname);
		}
		LuaValue.error("Failed to load prototype "+chunkname+" using mode '"+mode+"'");
		return null;
	}

	public Prototype compilePrototype(Reader reader, String chunkname) throws IOException {
		return compilePrototype(new UTF8Stream(reader), chunkname);
	}

	public Prototype compilePrototype(InputStream stream, String chunkname) throws IOException {
		if (compiler == null)
			LuaValue.error("No compiler.");
		return compiler.compile(stream, chunkname);
	}

	public Varargs yield(Varargs args) {
		LuaThread ct = getCurrentThread();
		if (ct == null || ct.isMainThread())
			throw new LuaError("cannot yield main thread");
		return ct.threadState.lua_yield_sync(args);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private Compiler compiler;
		private InterruptHandler interruptHandler;
		private InterruptHandler checkpointHandler;
		private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
		private ErrorReporter reportError;

		public LuaState build() {
			return new LuaState(this);
		}

		public Builder compiler(Compiler compiler) {
			Objects.requireNonNull(compiler, "compiler cannot be null");
			this.compiler = compiler;
			return this;
		}

		public Builder interruptHandler(InterruptHandler handler) {
			Objects.requireNonNull(handler, "handler cannot be null");
			interruptHandler = handler;
			return this;
		}

		/**
		 * Polls {@code handler} every {@link #checkpointInterval(int)} instructions from whichever thread is
		 * running Lua. It may return CONTINUE, SUSPEND (yield the current coroutine) or throw a {@link LuaError}
		 * to abort the running code, e.g. to enforce a time limit. After a throw it is polled again on the next
		 * instruction (not the next interval), so it can keep throwing until the offending code has unwound past
		 * any {@code pcall} that caught the error; returning CONTINUE restores the normal interval.
		 */
		public Builder checkpointHandler(InterruptHandler handler) {
			Objects.requireNonNull(handler, "handler cannot be null");
			checkpointHandler = handler;
			return this;
		}

		public Builder checkpointInterval(int instructions) {
			if (instructions <= 0) throw new IllegalArgumentException("checkpoint interval must be positive");
			checkpointInterval = instructions;
			return this;
		}

		public Builder errorReporter(ErrorReporter reporter) {
			Objects.requireNonNull(reporter, "reporter cannot be null");
			reportError = reporter;
			return this;
		}
	}

	public interface ErrorReporter {
		void report(Throwable error, Supplier<String> message);
	}
}
