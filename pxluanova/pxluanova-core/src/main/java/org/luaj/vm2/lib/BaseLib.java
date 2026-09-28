/*******************************************************************************
* Copyright (c) 2009 Luaj.org. All rights reserved.
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
package org.luaj.vm2.lib;

// TODO: Enhanced pairs/ipairs metatable support (from wagyourtail/luaj)
// - Fix pairs and ipairs to respect metatables properly
// - Improve setmetatable behavior for per-object metatables
// Reference: https://github.com/wagyourtail/luaj commits 95d05fb, 94aeeb6, f062b53

import java.io.IOException;
import java.io.InputStream;

import org.luaj.vm2.Lua;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaState;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.ProtectedCall;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.YieldContinuationException;

/**
 * Subclass of {@link LibFunction} which implements the lua basic library functions.
 * <p>
 * This contains all library functions listed as "basic functions" in the lua documentation.
 * The functions dofile and loadfile use the
 * {@link Globals#finder} instance to find resource files.
 * {@link BaseLib} implements
 * {@link ResourceFinder} using {@link Class#getResource(String)},
 * while {@link org.luaj.vm2.lib.jse.JseBaseLib} also supports file-based lookup.
 * The default loader chain in {@link PackageLib} will use these as well.
 * <p>
 * To use basic library functions that include a {@link ResourceFinder} based on
 * directory lookup, use {@link org.luaj.vm2.lib.jse.JseBaseLib} instead.
 * <p>
 * Typically, this library is included as part of a call to
 * {@link org.luaj.vm2.lib.jse.JsePlatform#standardGlobals()}
 * <pre> {@code
 * Globals globals = JsePlatform.standardGlobals();
 * globals.get("print").call(LuaValue.valueOf("hello, world"));
 * } </pre>
 * <p>
 * For special cases where the smallest possible footprint is desired,
 * a minimal set of libraries could be loaded
 * directly via {@link Globals#load(LuaValue)} using code such as:
 * <pre> {@code
 * Globals globals = new Globals();
 * globals.load(new JseBaseLib());
 * globals.get("print").call(LuaValue.valueOf("hello, world"));
 * } </pre>
 * Doing so will ensure the library is properly initialized
 * and loaded into the globals table.
 * <p>
 * This is a direct port of the corresponding library in C.
 * @see org.luaj.vm2.lib.jse.JseBaseLib
 * @see ResourceFinder
 * @see Globals#finder
 * @see LibFunction
 * @see org.luaj.vm2.lib.jse.JsePlatform
 * @see <a href="http://www.lua.org/manual/5.2/manual.html#6.1">Lua 5.2 Base Lib Reference</a>
 */
public class BaseLib extends TwoArgFunction implements ResourceFinder {
	
	LuaState state;
	

	/** Perform one-time initialization on the library by adding base functions
	 * to the supplied environment, and returning it as the return value.
	 * @param modname the module name supplied if this is loaded via 'require'.
	 * @param env the environment to load into, which must be a Globals instance.
	 */
	public LuaValue call(LuaValue modname, LuaValue env) {
		state = LuaState.current();
		state.finder = this;
		state.baselib = this;
		env.set( "_G", env );
		env.set( "_VERSION", Lua._VERSION );
		env.set("assert", new _assert());
		env.set("collectgarbage", new collectgarbage());
		env.set("dofile", new dofile());
		env.set("error", new error());
		env.set("getmetatable", new getmetatable());
		env.set("load", new load());
		env.set("loadfile", new loadfile());
		env.set("pcall", new pcall());
		env.set("print", new print(this));
		env.set("rawequal", new rawequal());
		env.set("rawget", new rawget());
		env.set("rawlen", new rawlen());
		env.set("rawset", new rawset());
		env.set("select", new select());
		env.set("setmetatable", new setmetatable());
		env.set("tonumber", new tonumber());
		env.set("tostring", new tostring());
		env.set("type", new type());
		env.set("xpcall", new xpcall());

		next next;
		env.set("next", next = new next());
		env.set("pairs", new pairs(next));
		env.set("ipairs", new ipairs());
		
		return env;
	}

	/** ResourceFinder implementation
	 * 
	 * Tries to open the file as a resource, which can work for JSE.
	 */
	public InputStream findResource(String filename) {
		return getClass().getResourceAsStream(filename.startsWith("/")? filename: "/"+filename);
	}

	
	// "assert", // ( v [,message] ) -> v, message | ERR
	static final class _assert extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			if ( !args.arg1().toboolean() )
				error( args.narg()>1? args.optjstring(2,"assertion failed!"): "assertion failed!" );
			return args;
		}
	}

	// "collectgarbage", // ( opt [,arg] ) -> value
	static final class collectgarbage extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			String s = args.optjstring(1, "collect");
			if ( "collect".equals(s) ) {
				System.gc();
				return ZERO;
			} else if ( "count".equals(s) ) {
				Runtime rt = Runtime.getRuntime();
				long used = rt.totalMemory() - rt.freeMemory();
				return varargsOf(valueOf(used/1024.), valueOf(used%1024));
			} else if ( "step".equals(s) ) {
				System.gc();
				return LuaValue.TRUE;
			} else {
				argerror(1, "invalid option '" + s + "'");
			}
			return NIL;
		}
	}

	// "dofile", // ( filename ) -> result1, ...
	final class dofile extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			args.argcheck(args.isstring(1) || args.isnil(1), 1, "filename must be string or nil");
			String filename = args.isstring(1)? args.tojstring(1): null;
			Varargs v = filename == null?
					loadStream( state.STDIN, "=stdin", "bt", state.globals ):
					loadFile( args.checkjstring(1), "bt", state.globals );
			return v.isnil(1)? error(v.tojstring(2)): v.arg1().invoke();
		}
	}

	// "error", // ( message [,level] ) -> ERR
	static final class error extends TwoArgFunction {
		public LuaValue call(LuaValue arg1, LuaValue arg2) {
			if (arg1.isnil()) throw new LuaError(NIL);
			if (!arg1.isstring() || arg2.optint(1) == 0) throw new LuaError(arg1);
			throw new LuaError(arg1.tojstring(), arg2.optint(1));
		}
	}

	// "getmetatable", // ( object ) -> table
	static final class getmetatable extends LibFunction {
		public LuaValue call() {
			return argerror(1, "value expected");
		}
		public LuaValue call(LuaValue arg) {
			LuaValue mt = arg.getmetatable();
			return mt!=null? mt.rawget(METATABLE).optvalue(mt): NIL;
		}
	}
	// "load", // ( ld [, source [, mode [, env]]] ) -> chunk | nil, msg
	final class load extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			LuaValue ld = args.arg1();
			if (!ld.isstring() && !ld.isfunction()) {
				throw new LuaError("bad argument #1 to 'load' (string or function expected, got " + ld.typename() + ")");
			}
			String source = args.optjstring(2, ld.isstring()? ld.tojstring(): "=(load)");
			String mode = args.optjstring(3, "bt");
			LuaValue env = args.optvalue(4, state.globals);
			return loadStream(ld.isstring()? ld.strvalue().toInputStream():
				new StringInputStream(ld.checkfunction()), source, mode, env);
		}
	}

	// "loadfile", // ( [filename [, mode [, env]]] ) -> chunk | nil, msg
	final class loadfile extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			args.argcheck(args.isstring(1) || args.isnil(1), 1, "filename must be string or nil");
			String filename = args.isstring(1)? args.tojstring(1): null;
			String mode = args.optjstring(2, "bt");
			LuaValue env = args.optvalue(3, state.globals);
			return filename == null?
				loadStream( state.STDIN, "=stdin", mode, env ):
				loadFile( filename, mode, env );
		}
	}
		
	// "pcall", // (f, arg1, ...) -> status, result1, ...
	final class pcall extends LuaContinuableFunction<Object> implements ProtectedCall {
		public boolean hasMessageHandler() { return false; }
		public Varargs invoke(Varargs args, Object continuation) {
			if (continuation != null) {
				if (continuation instanceof YieldContinuationException inner) {
					// Re-invoke the inner continuable function with its continuation
					Varargs innerResult;
					if (state != null && state.debuglib != null)
						state.debuglib.onCall(this);
					try {
						@SuppressWarnings("unchecked")
						LuaContinuableFunction<Object> innerFunc =
							(LuaContinuableFunction<Object>) inner.func;
						innerResult = innerFunc.invoke(inner.callArgs, inner.continuation);
					} catch ( LuaError le ) {
						final LuaValue m = le.getMessageObject();
						return varargsOf(FALSE, m!=null? m: NIL);
					} catch ( Exception e ) {
						final String m = e.getMessage();
						return varargsOf(FALSE, valueOf(m!=null? m: e.toString()));
					} finally {
						if (state != null && state.debuglib != null)
							state.debuglib.onReturn();
					}
					return varargsOf(TRUE, innerResult);
				}
				return varargsOf(TRUE, (Varargs) continuation);
			}
			LuaValue func = args.checkvalue(1);
			if (state != null && state.debuglib != null)
				state.debuglib.onCall(this);
			// An enclosing xpcall's handler must not see errors this pcall catches.
			final LuaThread et = state != null ? state.getCurrentThread() : null;
			final LuaValue preverror = et != null ? et.errorfunc : null;
			if (et != null)
				et.errorfunc = null;
			Varargs result;
			try {
				result = func.invoke(args.subargs(2));
			} catch ( YieldContinuationException yce ) {
				// Inner continuable function yielded — re-throw as our own yield
				// so we get re-invoked on resume, then re-invoke the inner.
				throw new YieldContinuationException(this, args, yce);
			} catch ( LuaError le ) {
				final LuaValue m = le.getMessageObject();
				return varargsOf(FALSE, m!=null? m: NIL);
			} catch ( Exception e ) {
				final String m = e.getMessage();
				return varargsOf(FALSE, valueOf(m!=null? m: e.toString()));
			} finally {
				if (et != null)
					et.errorfunc = preverror;
				if (state != null && state.debuglib != null)
					state.debuglib.onReturn();
			}
			LuaThread ct = state.getCurrentThread();
			if (ct != null && !ct.isMainThread()
				&& ct.threadState.isYieldPending()) {
				Varargs yielded = ct.threadState.result != null
					? ct.threadState.result : NONE;
				// Clear the yield state so it doesn't trigger again
				ct.threadState.yieldRequested = false;
				throw new YieldContinuationException(this, args, yielded);
			}
			return varargsOf(TRUE, result);
		}
	}

	// "print", // (...) -> void
	final class print extends VarArgFunction {
		final BaseLib baselib;
		print(BaseLib baselib) {
			this.baselib = baselib;
		}
		public Varargs invoke(Varargs args) {
			LuaValue tostring = state.globals.get("tostring");
			for ( int i=1, n=args.narg(); i<=n; i++ ) {
				if ( i>1 ) state.STDOUT.print( '\t' );
				LuaString s = tostring.call( args.arg(i) ).strvalue();
				// Raw bytes, like C Lua: decoding to a Java String would mangle non-UTF-8 data.
				state.STDOUT.write(s.m_bytes, s.m_offset, s.m_length);
			}
			state.STDOUT.print('\n');
			return NONE;
		}
	}
	

	// "rawequal", // (v1, v2) -> boolean
	static final class rawequal extends LibFunction {
		public LuaValue call() {
			return argerror(1, "value expected");
		}
		public LuaValue call(LuaValue arg) {
			return argerror(2, "value expected");
		}
		public LuaValue call(LuaValue arg1, LuaValue arg2) {
			return valueOf(arg1.raweq(arg2));
		}
	}

	// "rawget", // (table, index) -> value
	static final class rawget extends TableLibFunction {
		public LuaValue call(LuaValue arg) {
			return argerror(2, "value expected");
		}
		public LuaValue call(LuaValue arg1, LuaValue arg2) {
			return arg1.checktable().rawget(arg2);
		}
	}

	
	// "rawlen", // (v) -> value
	static final class rawlen extends LibFunction {
		public LuaValue call(LuaValue arg) {
			return valueOf(arg.rawlen());
		}
	}

	// "rawset", // (table, index, value) -> table
	static final class rawset extends TableLibFunction {
		public LuaValue call(LuaValue table) {
			return argerror(2,"value expected");
		}
		public LuaValue call(LuaValue table, LuaValue index) {
			return argerror(3,"value expected");
		}
		public LuaValue call(LuaValue table, LuaValue index, LuaValue value) {
			LuaTable t = table.checktable();
			if (!index.isvalidkey()) argerror(2, "table index is nil");
			t.rawset(index, value);
			return t;
		}
	}
	
	// "select", // (f, ...) -> value1, ...
	static final class select extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			int n = args.narg()-1;
			if ( args.arg1().equals(valueOf("#")) )
				return valueOf(n);
			int i = args.checkint(1);
			if ( i == 0 || i < -n )
				argerror(1,"index out of range");
			return args.subargs(i<0? n+i+2: i+1);
		}
	}
	
	// "setmetatable", // (table, metatable) -> table
	static final class setmetatable extends TableLibFunction {
		public LuaValue call(LuaValue table) {
			return argerror(2,"nil or table expected");
		}
		public LuaValue call(LuaValue table, LuaValue metatable) {
			final LuaValue mt0 = table.checktable().getmetatable();
			if ( mt0!=null && !mt0.rawget(METATABLE).isnil() )
				error("cannot change a protected metatable");
			return table.setmetatable(metatable.isnil()? null: metatable.checktable());
		}
	}
	
	// "tonumber", // (e [,base]) -> value
	static final class tonumber extends LibFunction {
		public LuaValue call(LuaValue e) {
			return e.tonumber();
		}
		public LuaValue call(LuaValue e, LuaValue base) {
			if (base.isnil())
				return e.tonumber();
			final int b = base.checkint();
			if ( b < 2 || b > 36 )
				argerror(2, "base out of range");
			return e.checkstring().tonumber(b);
		}
	}
	
	// "tostring", // (e) -> value
	static final class tostring extends LibFunction {
		public LuaValue call(LuaValue arg) {
			LuaValue h = arg.metatag(TOSTRING);
			if ( ! h.isnil() )
				return h.call(arg);
			LuaValue v = arg.tostring();
			if ( ! v.isnil() )
				return v;
			return valueOf(arg.tojstring());
		}
	}

	// "type",  // (v) -> value
	static final class type extends LibFunction {
		public LuaValue call(LuaValue arg) {
			return valueOf(arg.typename());
		}
	}

	// "xpcall", // (f, err) -> result1, ...
	final class xpcall extends LuaContinuableFunction<Object> implements ProtectedCall {
		public boolean hasMessageHandler() { return true; }
		public Varargs invoke(Varargs args, Object continuation) {
			if (continuation != null) {
				if (continuation instanceof YieldContinuationException inner) {
					final LuaThread t = state.getCurrentThread();
					final LuaValue preverror = t.errorfunc;
					t.errorfunc = args.checkvalue(2);
					try {
						if (state.debuglib != null)
							state.debuglib.onCall(this);
						Varargs innerResult;
						try {
							@SuppressWarnings("unchecked")
							LuaContinuableFunction<Object> innerFunc =
								(LuaContinuableFunction<Object>) inner.func;
							innerResult = innerFunc.invoke(inner.callArgs, inner.continuation);
						} catch ( LuaError le ) {
							final LuaValue m = le.getMessageObject();
							return varargsOf(FALSE, m!=null? m: NIL);
						} catch ( Exception e ) {
							final String m = e.getMessage();
							return varargsOf(FALSE, valueOf(m!=null? m: e.toString()));
						} finally {
							if (state.debuglib != null)
								state.debuglib.onReturn();
						}
						return varargsOf(TRUE, innerResult);
					} finally {
						t.errorfunc = preverror;
					}
				}
				return varargsOf(TRUE, (Varargs) continuation);
			}
			final LuaThread t = state.getCurrentThread();
			final LuaValue preverror = t.errorfunc;
			t.errorfunc = args.checkvalue(2);
			try {
				if (state.debuglib != null)
					state.debuglib.onCall(this);
				Varargs result;
				try {
					result = args.arg1().invoke(args.subargs(3));
				} catch ( YieldContinuationException yce ) {
					throw new YieldContinuationException(this, args, yce);
				} catch ( LuaError le ) {
					final LuaValue m = le.getMessageObject();
					return varargsOf(FALSE, m!=null? m: NIL);
				} catch ( Exception e ) {
					final String m = e.getMessage();
					return varargsOf(FALSE, valueOf(m!=null? m: e.toString()));
				} finally {
					if (state.debuglib != null)
						state.debuglib.onReturn();
				}
			if (t != null && !t.isMainThread()
				&& t.threadState.isYieldPending()) {
				Varargs yielded = t.threadState.result != null
					? t.threadState.result : NONE;
				t.threadState.yieldRequested = false;
				throw new YieldContinuationException(this, args, yielded);
			}
			return varargsOf(TRUE, result);
			} finally {
				t.errorfunc = preverror;
			}
		}
	}
	
	// "pairs" (t) -> iter-func, t, nil
	static final class pairs extends VarArgFunction {
		final next next;
		pairs(next next) {
			this.next = next;
		}
		public Varargs invoke(Varargs args) {
				return varargsOf( next, args.checktable(1), NIL );
		}
	}
	
	// // "ipairs", // (t) -> iter-func, t, 0
	static final class ipairs extends VarArgFunction {
		inext inext = new inext();
		public Varargs invoke(Varargs args) {
			return varargsOf( inext, args.checktable(1), ZERO );
		}
	}
	
	// "next"  ( table, [index] ) -> next-index, next-value
	static final class next extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			return args.checktable(1).next(args.arg(2));
		}
	}
	
	// "inext" ( table, [int-index] ) -> next-index, next-value
	static final class inext extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			return args.checktable(1).inext(args.arg(2));
		}
	}
	
	/**
	 * Load from a named file, returning the chunk or nil,error of can't load
	 * @param env
	 * @param mode
	 * @return Varargs containing chunk, or NIL,error-text on error
	 */
	public Varargs loadFile(String filename, String mode, LuaValue env) {
		InputStream is = state.finder.findResource(filename);
		if ( is == null )
			return varargsOf(NIL, valueOf("cannot open "+filename+": No such file or directory"));
		try {
			return loadStream(is, "@"+filename, mode, env);
		} finally {
			try {
				is.close();
			} catch ( Exception e ) {
				e.printStackTrace();
			}
		}
	}

	public Varargs loadStream(InputStream is, String chunkname, String mode, LuaValue env) {
		try {
			if ( is == null )
				return varargsOf(NIL, valueOf("not found: "+chunkname));
			return state.load(is, chunkname, mode, env);
		} catch (Exception e) {
			return varargsOf(NIL, valueOf(e.getMessage()));
		}
	}
	
	
	private static class StringInputStream extends InputStream {
		final LuaValue func;
		byte[] bytes;
		int offset, remaining = 0;
		StringInputStream(LuaValue func) {
			this.func = func;
		}
		public int read() throws IOException {
			if ( remaining < 0 )
				return -1;
			if ( remaining == 0 ) {
				LuaValue s = func.call();
				if ( s.isnil() )
					return remaining = -1;
				LuaString ls = s.strvalue();
				bytes = ls.m_bytes;
				offset = ls.m_offset;
				remaining = ls.m_length;
				if (remaining <= 0)
					return remaining = -1; // an empty string ends the chunk, like nil
			}
			--remaining;
			return 0xFF&bytes[offset++];
		}
	}
}
