/*******************************************************************************
 * Copyright (c) 2009-2011 Luaj.org. All rights reserved.
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
package org.luaj.vm2.lib.jse;

import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaState;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.ResourceFinder;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.Utf8Lib;

/** The {@link org.luaj.vm2.lib.jse.JsePlatform} class is a convenience class to standardize
 * how globals tables are initialized for the JSE platform.
 * <p>
 * It is used to allocate either a set of standard globals using
 * {@link #standardGlobals()} or debug globals using {@link #debugGlobals()}
 * <p>
 * A simple example of initializing globals and using them from Java is:
 * <pre> {@code
 * Globals globals = JsePlatform.standardGlobals();
 * globals.get("print").call(LuaValue.valueOf("hello, world"));
 * } </pre>
 * <p>
 * Once globals are created, a simple way to load and run a script is:
 * <pre> {@code
 * globals.load( new FileInputStream("main.lua"), "main.lua" ).call();
 * } </pre>
 * <p>
 * although {@code require} could also be used:
 * <pre> {@code
 * globals.get("require").call(LuaValue.valueOf("main"));
 * } </pre>
 * For this to succeed, the file "main.lua" must be in the current directory or a resource.
 * See {@link org.luaj.vm2.lib.jse.JseBaseLib} for details on finding scripts using {@link ResourceFinder}.
 * <p>
 * The standard globals will contain all standard libraries plus {@code luajava}:
 * <ul>
 * <li>{@link Globals}</li>
 * <li>{@link org.luaj.vm2.lib.jse.JseBaseLib}</li>
 * <li>{@link PackageLib}</li>
 * <li>{@link Bit32Lib}</li>
 * <li>{@link TableLib}</li>
 * <li>{@link StringLib}</li>
 * <li>{@link CoroutineLib}</li>
 * <li>{@link org.luaj.vm2.lib.jse.JseMathLib}</li>
 * <li>{@link org.luaj.vm2.lib.jse.JseIoLib}</li>
 * <li>{@link org.luaj.vm2.lib.jse.JseOsLib}</li>
 * <li>{@link LuajavaLib}</li>
 * </ul>
 * In addition, the {@link LuaC} compiler is installed so lua files may be loaded in their source form.
 * <p>
 * The debug globals are simply the standard globals plus the {@code debug} library {@link DebugLib}.
 * <p>
 * The class ensures that initialization is done in the correct order.
 * <p>
 * <b>Virtual Threads:</b> Coroutines use Java virtual threads by default (Java 21+),
 * enabling millions of concurrent coroutines with minimal memory overhead.
 * To use platform threads instead, set {@code globals.coroutineThreadFactory = LuaThread.PLATFORM_THREAD_FACTORY}.
 * 
 * @see Globals
 * @see org.luaj.vm2.lib.jse.JsePlatform
 */
public class JsePlatform {

	/**
	 * Create a standard set of globals for JSE including all the libraries.
	 * Coroutines use virtual threads by default.
	 * 
	 * @return Table of globals initialized with the standard JSE libraries
	 * @see #debugGlobals()
	 * @see org.luaj.vm2.lib.jse.JsePlatform
	 */
	public static LuaState standardState() {
		LuaState state = LuaState.builder().build();
		state.globals.load(new JseBaseLib());
		state.globals.load(new PackageLib());
		state.globals.load(new Bit32Lib());
		state.globals.load(new TableLib());
		state.globals.load(new Utf8Lib());
		state.globals.load(new JseStringLib());
		state.globals.load(new CoroutineLib());
		state.globals.load(new JseMathLib());
		state.globals.load(new JseIoLib());
		state.globals.load(new JseOsLib());
		state.globals.load(new LuajavaLib());
		LoadState.install(state);
		LuaC.install(state);
		return state;
	}

	/**
	 * Create a standard set of globals for JSE including all the libraries.
	 * Coroutines use virtual threads by default.
	 * 
	 * @return Table of globals initialized with the standard JSE libraries
	 * @see #debugGlobals()
	 * @see org.luaj.vm2.lib.jse.JsePlatform
	 * @deprecated Use {@link #standardState()} instead.
	 */
	@Deprecated
	public static LuaTable standardGlobals() {
		return standardState().globals;
	}

	/** Create standard globals including the {@link DebugLib} library.
	 * 
	 * @return Table of globals initialized with the standard JSE and debug libraries
	 * @see #standardState()
	 * @see org.luaj.vm2.lib.jse.JsePlatform
	 * @see DebugLib
	 */
	public static LuaState debugState() {
		LuaState state = standardState();
		state.globals.load(new DebugLib());
		return state;
	}

	/** Create standard globals including the {@link DebugLib} library.
	 * 
	 * @return Table of globals initialized with the standard JSE and debug libraries
	 * @see #standardGlobals()
	 * @see org.luaj.vm2.lib.jse.JsePlatform
	 * @see DebugLib
	 * @deprecated Use {@link #debugState()} instead.
	 */
	@Deprecated
	public static LuaTable debugGlobals() {
		return debugState().globals;
	}


	/** Simple wrapper for invoking a lua function with command line arguments.
	 * The supplied function is first given a new LuaState object as its environment
	 * then the program is run with arguments.
	 * @return {@link Varargs} containing any values returned by mainChunk.
	 */
	public static Varargs luaMain(LuaValue mainChunk, String[] args) {
		LuaState state = standardState();
		int n = args.length;
		LuaValue[] vargs = new LuaValue[args.length];
		for (int i = 0; i < n; ++i)
			vargs[i] = LuaValue.valueOf(args[i]);
		LuaValue arg = LuaValue.listOf(vargs);
		arg.set("n", n);
		state.globals.set("arg", arg);
		mainChunk.initupvalue1(state.globals);
		return mainChunk.invoke(LuaValue.varargsOf(vargs));
	}
}
