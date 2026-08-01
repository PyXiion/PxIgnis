package org.luaj.vm2.lib;

import org.luaj.vm2.Varargs;

/**
 * Base class for Java-implemented Lua functions that allow
 * coroutine.yield to propagate through them transparently.
 *
 * Subclassing contract:
 * <ul>
 *   <li>Override {@code invoke(Varargs args, T continuation)}.</li>
 *   <li>On first entry, {@code continuation == null}. Run the inner function.</li>
 *   <li>If the inner function yields, throw
 *       {@link org.luaj.vm2.YieldContinuationException} with a state value;
 *       that value will be passed back as {@code continuation} on resume.</li>
 *   <li>On resume, {@code invoke} is called again with the state as
 *       {@code continuation}. Return the function's result Varargs;
 *       do NOT re-invoke the inner function (it has already completed).</li>
 *   <li>If the inner function returns normally, return the result Varargs.</li>
 * </ul>
 *
 * <p>The type parameter {@code T} is the shape of the continuation state,
 * chosen by the subclass. It can be any Object (Boolean, Varargs, a custom
 * record, a Map, etc.). The VM treats it opaquely.</p>
 *
 * <p>Example: see {@link BaseLib#pcall}.</p>
 */
public abstract class LuaContinuableFunction<T> extends VarArgFunction {
	@Override
	public final Varargs invoke(Varargs args) {
		return invoke(args, null);
	}

	public abstract Varargs invoke(Varargs args, T continuation);
}
