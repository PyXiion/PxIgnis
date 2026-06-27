package org.luaj.vm2.lib.jse;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

class SyncCompiledFunction extends VarArgFunction {

	private final LuaState state;
	private final LuaFunction compiled;

	SyncCompiledFunction(LuaState state, LuaFunction compiled) {
		this.state = state;
		this.compiled = compiled;
	}

	public Varargs invoke(Varargs args) {
		if (state == null) {
			throw new LuaError("nova.sync must be called on the main server thread,");
		}
		LuaState currentState = LuaState.current();
		if (currentState == state) {
			state.enterSyncCompiled();
			try {
				return compiled.invoke(args);
			} finally {
				state.leaveSyncCompiled();
			}
		}
		return compiled.invoke(args);
	}

	public LuaValue call() {
		if (state == null) {
			throw new LuaError("nova.sync must be called on the main server thread,");
		}
		LuaState currentState = LuaState.current();
		if (currentState == state) {
			state.enterSyncCompiled();
			try {
				return compiled.call();
			} finally {
				state.leaveSyncCompiled();
			}
		}
		return compiled.call();
	}

	public LuaValue call(LuaValue a) {
		if (state == null) {
			throw new LuaError("nova.sync must be called on the main server thread,");
		}
		LuaState currentState = LuaState.current();
		if (currentState == state) {
			state.enterSyncCompiled();
			try {
				return compiled.call(a);
			} finally {
				state.leaveSyncCompiled();
			}
		}
		return compiled.call(a);
	}

	public LuaValue call(LuaValue a, LuaValue b) {
		if (state == null) {
			throw new LuaError("nova.sync must be called on the main server thread,");
		}
		LuaState currentState = LuaState.current();
		if (currentState == state) {
			state.enterSyncCompiled();
			try {
				return compiled.call(a, b);
			} finally {
				state.leaveSyncCompiled();
			}
		}
		return compiled.call(a, b);
	}

	public LuaValue call(LuaValue a, LuaValue b, LuaValue c) {
		if (state == null) {
			throw new LuaError("nova.sync must be called on the main server thread,");
		}
		LuaState currentState = LuaState.current();
		if (currentState == state) {
			state.enterSyncCompiled();
			try {
				return compiled.call(a, b, c);
			} finally {
				state.leaveSyncCompiled();
			}
		}
		return compiled.call(a, b, c);
	}
}
