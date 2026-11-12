package org.luaj.vm2;

import junit.framework.TestCase;

import org.luaj.vm2.lib.jse.JsePlatform;

public class SyncCoroutineTest extends TestCase {

	LuaState state;

	protected void setUp() throws Exception {
		state = JsePlatform.standardState();
	}

	public void testSyncThreadCreateAndResume() {
		LuaValue func = state.load("return 42", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("42", result.arg(2).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadYieldAndResume() {
		LuaValue func = state.load(
			"coroutine.yield('hello')\n" +
			"return 'world'", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("hello", result.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());

		result = co.resume(LuaValue.valueOf("resume_val"));
		assertTrue(result.arg1().toboolean());
		assertEquals("world", result.arg(2).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadPassValues() {
		LuaValue func = state.load(
			"local a, b = ...\n" +
			"local x, y = coroutine.yield(a + b)\n" +
			"return x * y", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.varargsOf(
			LuaValue.valueOf(10), LuaValue.valueOf(20)));
		assertTrue(result.arg1().toboolean());
		assertEquals(30, result.arg(2).toint());

		result = co.resume(LuaValue.varargsOf(
			LuaValue.valueOf(3), LuaValue.valueOf(4)));
		assertTrue(result.arg1().toboolean());
		assertEquals(12, result.arg(2).toint());
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadMultipleYields() {
		LuaValue func = state.load(
			"local total = 0\n" +
			"for i = 1, 3 do\n" +
			"  total = total + coroutine.yield(i)\n" +
			"end\n" +
			"return total", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);

		Varargs r = co.resume(LuaValue.NONE);
		assertTrue(r.arg1().toboolean());
		assertEquals(1, r.arg(2).toint());

		r = co.resume(LuaValue.valueOf(10));
		assertTrue(r.arg1().toboolean());
		assertEquals(2, r.arg(2).toint());

		r = co.resume(LuaValue.valueOf(20));
		assertTrue(r.arg1().toboolean());
		assertEquals(3, r.arg(2).toint());

		r = co.resume(LuaValue.valueOf(30));
		assertTrue(r.arg1().toboolean());
		assertEquals(60, r.arg(2).toint());
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadCannotYieldMainThread() {
		try {
			state.yield(LuaValue.NONE);
			fail("Expected LuaError");
		} catch (LuaError e) {
			assertTrue(e.getMessage().contains("cannot yield main thread"));
		}
	}

	public void testSyncCoroutineCreateFromLua() {
		LuaValue func = state.load(
			"local co = coroutine.create(function(x)\n" +
			"  coroutine.yield(x * 2)\n" +
			"  return x * 3\n" +
			"end)\n" +
			"local ok, val = coroutine.resume(co, 5)\n" +
			"assert(ok)\n" +
			"local ok2, val2 = coroutine.resume(co, 0)\n" +
			"assert(ok2)\n" +
			"return val, val2", "test").checkfunction();
		Varargs result = func.call();
		assertEquals("10", result.tojstring());
	}

	public void testSyncThreadError() {
		LuaValue func = state.load("error('bad thing')", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertFalse(result.arg1().toboolean());
		assertTrue(result.arg(2).tojstring().contains("bad thing"));
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadErrorAfterYield() {
		LuaValue func = state.load(
			"coroutine.yield('ok')\n" +
			"error('after yield')", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("ok", result.arg(2).tojstring());

		result = co.resume(LuaValue.NONE);
		assertFalse(result.arg1().toboolean());
		assertTrue(result.arg(2).tojstring().contains("after yield"));
		assertEquals("dead", co.getStatus());
	}

	public void testSyncThreadPcall() {
		LuaValue func = state.load(
			"local ok, err = pcall(error, 'caught')\n" +
			"return ok, err", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertFalse(result.arg(2).toboolean());
		assertTrue(result.arg(3).tojstring().contains("caught"));
	}

	public void testSyncThreadTailCalls() {
		LuaValue func = state.load(
			"local function tail_sum(n, acc)\n" +
			"  if n <= 0 then return acc end\n" +
			"  return tail_sum(n - 1, acc + n)\n" +
			"end\n" +
			"return tail_sum(100, 0)", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals(5050, result.arg(2).toint());
	}

	public void testSyncThreadTailCallsDeep() {
		LuaValue func = state.load(
			"local function tail_sum(n, acc)\n" +
			"  if n <= 0 then return acc end\n" +
			"  return tail_sum(n - 1, acc + n)\n" +
			"end\n" +
			"return tail_sum(10000, 0)", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals(50005000, result.arg(2).toint());
	}

	public void testSyncThreadClosure() {
		LuaValue func = state.load(
			"local x = 10\n" +
			"local function inner(y)\n" +
			"  return x + y\n" +
			"end\n" +
			"x = 20\n" +
			"return inner(5)", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals(25, result.arg(2).toint());
	}

	public void testSyncThreadNestedCoroutines() {
		LuaValue func = state.load(
			"local inner = coroutine.wrap(function(x)\n" +
			"  local y = coroutine.yield(x + 1)\n" +
			"  return y + x\n" +
			"end)\n" +
			"local a = inner(10)\n" +
			"local b = inner(20)\n" +
			"return a + b", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals(41, result.arg(2).toint());
	}

	public void testSyncIsYieldable() {
		LuaValue func = state.load(
			"return coroutine.isyieldable()", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertTrue(result.arg(2).toboolean());
	}

	public void testSyncThreadCannotResumeDead() {
		LuaValue func = state.load("return 'done'", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		co.resume(LuaValue.NONE);
		Varargs result = co.resume(LuaValue.NONE);
		assertFalse(result.arg1().toboolean());
		assertTrue(result.arg(2).tojstring().contains("dead"));
	}

	public void testSyncThreadCannotResumeRunning() {
		// Running state: a coroutine can't resume itself
		LuaValue func = state.load(
			"local co = coroutine.running()\n" +
			"local ok, err = coroutine.resume(co)\n" +
			"return ok, err", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertFalse(result.arg(2).toboolean());
		assertTrue(result.arg(3).tojstring().contains("non-suspended"));
	}

	public void testSyncThreadCoroutineStatus() {
		LuaValue func = state.load(
			"local co = coroutine.create(function()\n" +
			"  return coroutine.status(coroutine.running())\n" +
			"end)\n" +
			"local ok, status = coroutine.resume(co)\n" +
			"return status", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("running", result.arg(2).tojstring());
	}

	public void testSyncThreadWrap() {
		LuaValue func = state.load(
			"local f = coroutine.wrap(function(...)\n" +
			"  local args = {...}\n" +
			"  local sum = 0\n" +
			"  for _, v in ipairs(args) do\n" +
			"    sum = sum + v\n" +
			"  end\n" +
			"  return sum\n" +
			"end)\n" +
			"return f(1, 2, 3, 4)", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals(10, result.arg(2).toint());
	}

	public void testPcallYieldPropagates() {
		LuaValue func = state.load(
			"local ok, val = pcall(function() return coroutine.yield('from-yield') end)\n" +
			"return ok, val", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs first = co.resume(LuaValue.NONE);
		assertTrue(first.arg1().toboolean());
		assertEquals("from-yield", first.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());
		// yield returns the resume values; the function returns them, so pcall returns true, them
		Varargs second = co.resume(LuaValue.valueOf("from-yield"));
		assertTrue(second.arg1().toboolean());
		assertTrue(second.arg(2).toboolean());
		assertEquals("from-yield", second.arg(3).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testPcallYieldMultipleValues() {
		LuaValue func = state.load(
			"local ok, a, b, c = pcall(function() return coroutine.yield(1, 2, 3) end)\n" +
			"return ok, a, b, c", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs first = co.resume(LuaValue.NONE);
		assertTrue(first.arg1().toboolean());
		assertEquals(1, first.arg(2).toint());
		assertEquals(2, first.arg(3).toint());
		assertEquals(3, first.arg(4).toint());
		assertEquals("suspended", co.getStatus());
		Varargs second = co.resume(LuaValue.varargsOf(new LuaValue[] {
			LuaValue.valueOf(1), LuaValue.valueOf(2), LuaValue.valueOf(3) }));
		assertTrue(second.arg1().toboolean());
		assertTrue(second.arg(2).toboolean());
		assertEquals(1, second.arg(3).toint());
		assertEquals(2, second.arg(4).toint());
		assertEquals(3, second.arg(5).toint());
		assertEquals("dead", co.getStatus());
	}

	public void testXpcallYieldPropagates() {
		LuaValue func = state.load(
			"local ok, val = xpcall(function() return coroutine.yield('xpcall-yield') end, function(e) return e end)\n" +
			"return ok, val", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs first = co.resume(LuaValue.NONE);
		assertTrue(first.arg1().toboolean());
		assertEquals("xpcall-yield", first.arg(2).tojstring());
		assertEquals("suspended", co.getStatus());
		Varargs second = co.resume(LuaValue.valueOf("xpcall-yield"));
		assertTrue(second.arg1().toboolean());
		assertTrue(second.arg(2).toboolean());
		assertEquals("xpcall-yield", second.arg(3).tojstring());
		assertEquals("dead", co.getStatus());
	}

	public void testPcallErrorNotMaskedAsYield() {
		LuaValue func = state.load(
			"local ok, err = pcall(error, 'boom')\n" +
			"if not ok then return 'caught: ' .. err end\n" +
			"return 'unreachable'", "test").checkfunction();
		LuaThread co = new LuaThread(state, func);
		Varargs result = co.resume(LuaValue.NONE);
		assertTrue(result.arg1().toboolean());
		assertEquals("caught: boom", result.arg(2).tojstring());
		assertEquals("dead", co.getStatus());
	}
}
