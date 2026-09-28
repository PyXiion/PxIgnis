package org.luaj.vm2;

/**
 * Marker for pcall-like library functions. When a coroutine calls one of these with a function argument, the
 * frame interpreter runs the callee as a protected frame instead of a nested Java call, so the callee can
 * yield like any other Lua code.
 */
public interface ProtectedCall {
	/** true for xpcall(f, handler, ...), false for pcall(f, ...). */
	boolean hasMessageHandler();
}
