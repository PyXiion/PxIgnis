package org.luaj.vm2;

import junit.framework.TestCase;

import org.luaj.vm2.lib.jse.JsePlatform;

public class LuaTransferTest extends TestCase {

	private LuaState from;
	private LuaState to;

	protected void setUp() {
		from = JsePlatform.standardState();
		to = JsePlatform.standardState();
	}

	private Varargs transfer(Varargs values) {
		return LuaTransfer.materialize(LuaTransfer.snapshot(values, from, LuaTransfer.NO_USERDATA), to, LuaTransfer.NO_USERDATA);
	}

	private LuaValue transfer(String expr) {
		return transfer(from.load("return " + expr, "src").call()).arg1();
	}

	public void testPrimitivesPassThrough() {
		Varargs v = transfer(LuaValue.varargsOf(new LuaValue[] {
			LuaValue.NIL, LuaValue.TRUE, LuaValue.valueOf(1.5), LuaValue.valueOf("s") }));
		assertEquals(4, v.narg());
		assertTrue(v.arg1().isnil());
		assertTrue(v.arg(2).toboolean());
		assertEquals(1.5, v.arg(3).todouble());
		assertEquals("s", v.arg(4).tojstring());
	}

	public void testTablesAreCopiedWithCyclesAndSharing() {
		LuaValue t = transfer("(function() local shared = {1} local t = {a = shared, b = shared} t.self = t return t end)()");
		assertNotSame(from.globals, t);
		assertSame(t, t.get("self"));
		assertSame(t.get("a"), t.get("b"));
		assertEquals(1, t.get("a").get(1).toint());
	}

	public void testCopyIsIndependent() {
		LuaValue src = from.load("data = {n = 1} return data", "src").call();
		LuaValue copy = transfer((Varargs) src).arg1();
		copy.set("n", LuaValue.valueOf(2));
		assertEquals(1, src.get("n").toint());
	}

	public void testMetatableIsCopied() {
		LuaValue t = transfer("setmetatable({}, {__index = function(t, k) return k .. '!' end})");
		assertEquals("x!", t.get("x").tojstring());
	}

	public void testClosureRunsWithTargetGlobalsAndCopiedUpvalues() {
		to.globals.set("where", LuaValue.valueOf("target"));
		from.globals.set("where", LuaValue.valueOf("source"));
		LuaValue f = transfer("(function() local n = 41 return function() n = n + 1 return where, n end end)()");
		Varargs r = f.invoke(LuaValue.NONE);
		assertEquals("target", r.arg1().tojstring());
		assertEquals(42, r.arg(2).toint());
		assertSame(to, ((LuaClosure) f).state);
	}

	public void testSharedUpvalueStaysShared() {
		LuaValue t = transfer("(function() local n = 0 return {inc = function() n = n + 1 end, get = function() return n end} end)()");
		t.get("inc").call();
		t.get("inc").call();
		assertEquals(2, t.get("get").call().toint());
	}

	public void testLibraryFunctionsAreRelinked() {
		LuaValue f = transfer("(function() local floor, fmt = math.floor, string.format return function(x) return fmt('%d', floor(x)) end end)()");
		assertEquals("3", f.call(LuaValue.valueOf(3.7)).tojstring());
		assertSame(to.globals.get("math"), transfer("math"));
		assertSame(to.globals.get("print"), transfer("print"));
	}

	public void testMissingLibraryIsAnError() {
		from.globals.set("host", LuaValue.tableOf(new LuaValue[] { LuaValue.valueOf("f"), new org.luaj.vm2.lib.ZeroArgFunction() {
			public LuaValue call() { return NIL; }
		} }));
		try {
			transfer("host");
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'host' is not available"));
		}
	}

	public void testUserdataUsesPolicy() {
		try {
			transfer(LuaValue.userdataOf(new Object()));
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("cannot send value to another Lua state"));
		}
		LuaTransfer.UserdataPolicy copyInts = new LuaTransfer.UserdataPolicy() {
			public Object export(LuaUserdata u) { return u.m_instance; }
			public LuaValue materialize(Object exported, LuaState target) { return LuaValue.userdataOf(exported); }
		};
		LuaValue u = LuaValue.userdataOf(7);
		Varargs r = LuaTransfer.materialize(LuaTransfer.snapshot(u, from, copyInts), to, copyInts);
		assertEquals(7, r.arg1().touserdata());
	}

	public void testErrorNamesTheUpvalue() {
		try {
			transfer("(function() local co = coroutine.create(print) return function() return co end end)()");
			fail("expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage(), e.getMessage().contains("upvalue 'co'"));
		}
	}
}
