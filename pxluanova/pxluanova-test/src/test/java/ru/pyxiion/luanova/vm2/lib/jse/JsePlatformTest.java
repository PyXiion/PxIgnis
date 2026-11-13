package ru.pyxiion.luanova.vm2.lib.jse;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.LuaState;
import ru.pyxiion.luanova.vm2.LuaValue;
import ru.pyxiion.luanova.vm2.Varargs;


public class JsePlatformTest extends TestCase {
	public void testLuaMainPassesArguments() {
		LuaState state = JsePlatform.standardState();
		LuaValue chunk = state.load("return #arg, arg.n, arg[2], arg[1]");
		Varargs results = JsePlatform.luaMain(chunk, new String[] { "aaa", "bbb" });
		assertEquals(results.narg(), 4);
		assertEquals(results.arg(1), LuaValue.valueOf(2));
		assertEquals(results.arg(2), LuaValue.valueOf(2));
		assertEquals(results.arg(3), LuaValue.valueOf("bbb"));
		assertEquals(results.arg(4), LuaValue.valueOf("aaa"));
	}
}
