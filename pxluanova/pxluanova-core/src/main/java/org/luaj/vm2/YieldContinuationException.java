package org.luaj.vm2;

/**
 * Internal sentinel thrown by a LuaContinuableFunction to propagate a
 * coroutine yield up through its call boundary. Carries the function,
 * its arguments, and a continuation payload (opaque Object chosen by
 * the function). The OP_CALL handler in FrameInterpreter catches this,
 * stores the contents on the calling frame, and on resume re-invokes
 * {@code func} with {@code callArgs} and {@code continuation}.
 */
public final class YieldContinuationException extends LuaError {
	public final LuaValue func;
	public final Varargs callArgs;
	public final Object continuation;

	public YieldContinuationException(LuaValue func, Varargs callArgs, Object continuation) {
		super((String) null);
		this.func = func;
		this.callArgs = callArgs;
		this.continuation = continuation;
	}

	@Override
	public String getMessage() {
		return null;
	}
}
