package org.luaj.vm2;

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

	static void setCurrent(LuaState state) {
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

	private volatile boolean interrupted;
	private final InterruptHandler interruptHandler;

	int javaCallDepth = 0;

	volatile LuaThread currentThread;
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

	public LuaThread.ThreadFactory coroutineThreadFactory = LuaThread.VIRTUAL_THREAD_FACTORY;

	public LuaState() {
		this(new Builder());
	}

	private LuaState(Builder builder) {
		compiler = builder.compiler;
		interruptHandler = builder.interruptHandler;
		reportError = builder.reportError;

		globals = new LuaTable();
		globals.set("_G", globals);
		globals.set("_VERSION", Lua._VERSION);
		mainThread = currentThread = new LuaThread(this);
		setCurrent(this);
	}

	public GlobalRegistry registry() {
		return registry;
	}

	public LuaThread getMainThread() {
		return mainThread;
	}

	public LuaThread getCurrentThread() {
		return currentThread;
	}

	void setCurrentThread(LuaThread thread) {
		currentThread = thread;
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
		switch (interruptHandler.interrupted()) {
			case CONTINUE -> {}
			case SUSPEND -> {
			if (currentThread == null || currentThread.threadState.status != LuaThread.STATUS_RUNNING) {
				throw new IllegalStateException("Cannot suspend non-running coroutine");
			}
			if (currentThread.isMainThread())
				throw new LuaError("cannot yield main thread");
			currentThread.threadState.yieldIsInterrupt = true;
			currentThread.threadState.lua_yield_sync(LuaValue.NONE);
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
		if (currentThread != null && !currentThread.isMainThread()) {
			currentThread.threadState.syncCompiledDepth++;
		}
	}

	public void leaveSyncCompiled() {
		if (currentThread != null && !currentThread.isMainThread()) {
			currentThread.threadState.syncCompiledDepth--;
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
		if (currentThread == null || currentThread.isMainThread())
			throw new LuaError("cannot yield main thread");
		return currentThread.threadState.lua_yield_sync(args);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private Compiler compiler;
		private InterruptHandler interruptHandler;
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
