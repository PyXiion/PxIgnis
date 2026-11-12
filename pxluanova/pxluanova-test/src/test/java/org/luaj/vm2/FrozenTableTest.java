package org.luaj.vm2;

import junit.framework.TestCase;

import org.luaj.vm2.lib.jse.JsePlatform;

public class FrozenTableTest extends TestCase {

	private LuaState state;

	protected void setUp() {
		state = JsePlatform.standardState();
		LuaTable t = new LuaTable();
		t.set("a", LuaValue.valueOf(1));
		t.set(1, LuaValue.valueOf("x"));
		t.set(2, LuaValue.valueOf("y"));
		state.globals.set("t", t);
		t.freeze();
	}

	private Varargs run(String code) {
		return state.load(code, "test").invoke();
	}

	private void assertFrozenFailure(String stmt) {
		Varargs r = run("return pcall(function() " + stmt + " end)");
		assertFalse(stmt, r.arg1().toboolean());
		assertTrue(stmt + " -> " + r.arg(2), r.arg(2).tojstring().contains("frozen table"));
	}

	public void testWritesFail() {
		assertFrozenFailure("t.x = 1");
		assertFrozenFailure("t.a = nil");
		assertFrozenFailure("t[1] = 1");
		assertFrozenFailure("t[10] = 1");
		assertFrozenFailure("rawset(t, 'x', 1)");
		assertFrozenFailure("table.insert(t, 1)");
		assertFrozenFailure("table.remove(t)");
		assertFrozenFailure("table.sort(t)");
		assertFrozenFailure("setmetatable(t, {})");
	}

	public void testReadsStillWork() {
		assertEquals(1, run("return t.a").arg1().toint());
		assertEquals(2, run("return #t").arg1().toint());
		assertEquals("xy", run("local s = '' for _, v in ipairs(t) do s = s .. v end return s").arg1().tojstring());
		assertEquals(3, run("local n = 0 for _ in pairs(t) do n = n + 1 end return n").arg1().toint());
		assertEquals("x", run("return select(2, next(t))").arg1().tojstring());
		assertTrue(((LuaTable) state.globals.get("t")).isFrozen());
	}
}
