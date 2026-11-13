package ru.pyxiion.luanova.vm2;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform;

public class StringMetatablePerStateTest extends TestCase {

	public void testStringMetatableIsPerState() {
		LuaState a = JsePlatform.standardState();
		a.load("function string.shout(s) return s:upper() .. '!' end", "a").call();
		assertEquals("X!", a.load("return ('x'):shout()", "a2").call().tojstring());

		LuaState b = JsePlatform.standardState();
		assertSame(b, LuaState.current());
		Varargs r = b.load("return pcall(function() return ('x'):shout() end)", "b").invoke();
		assertFalse(r.arg1().toboolean());
		assertEquals("X", b.load("return ('x'):upper()", "b2").call().tojstring());

		LuaState.setCurrent(a);
		assertEquals("X!", a.load("return ('x'):shout()", "a3").call().tojstring());
	}
}
