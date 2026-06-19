package org.luaj.vm2.lib.jse;

import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaState;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.luajc.SyncCompiler;

public class NovaLib extends TwoArgFunction {

	private final SyncCompiler compiler = new SyncCompiler();

	public LuaValue call(LuaValue modname, LuaValue env) {
		LuaTable nova = new LuaTable();
		nova.set("sync", new sync());
		env.set("nova", nova);
		return nova;
	}

	final class sync extends OneArgFunction {
		public LuaValue call(LuaValue arg) {
			if (!(arg instanceof LuaClosure closure)) {
				throw new LuaError("nova.sync: expected function (LuaClosure), got " + arg.typename());
			}
			LuaState state = LuaState.current();
			LuaFunction compiled = compiler.compile(closure);
			return new SyncCompiledFunction(state, compiled);
		}
	}
}
