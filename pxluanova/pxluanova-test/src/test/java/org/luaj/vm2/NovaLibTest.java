package org.luaj.vm2;

import junit.framework.TestCase;
import org.luaj.vm2.lib.jse.JsePlatform;

public class NovaLibTest extends TestCase {

	LuaState state;

	protected void setUp() throws Exception {
		state = JsePlatform.standardState();
	}

	private LuaValue sync(String funcExpr) {
		LuaValue loader = state.load(
			"local f = " + funcExpr + "\n" +
			"return nova.sync(f)", "test").checkfunction();
		return loader.call();
	}

	public void testBasicFunction() {
		LuaValue fn = sync("function(x) return x + 1 end");
		assertEquals(42, fn.call(LuaValue.valueOf(41)).toint());
	}

	public void testMultipleArgs() {
		LuaValue fn = sync("function(x, y) return x * y end");
		assertEquals(20, fn.call(LuaValue.valueOf(4), LuaValue.valueOf(5)).toint());
	}

	public void testCapturedLocal() {
		LuaValue loader = state.load(
			"local cap = 10\n" +
			"local f = function(x) return x + cap end\n" +
			"return nova.sync(f)", "test").checkfunction();
		LuaValue fn = loader.call();
		assertEquals(25, fn.call(LuaValue.valueOf(15)).toint());
	}

	public void testGlobalsAccess() {
		LuaValue fn = sync("function(x) return math.sin(x) end");
		double result = fn.call(LuaValue.valueOf(0.5)).todouble();
		assertEquals(Math.sin(0.5), result, 1e-15);
	}

	public void testMultipleCalls() {
		LuaValue fn = sync("function(x) return x * 2 end");
		for (int i = 0; i < 10; i++) {
			assertEquals(i * 2, fn.call(LuaValue.valueOf(i)).toint());
		}
	}

	public void testErrorOnNonClosure() {
		LuaValue func = state.load(
			"return nova.sync(42)", "test").checkfunction();
		try {
			func.call();
			fail("Expected LuaError");
		} catch (LuaError e) {
			String msg = e.getMessage();
			assertTrue("Got: " + msg, msg.contains("LuaClosure"));
		}
	}

	public void testYieldInsideSyncThrows() {
		LuaValue func = state.load(
			"local f = nova.sync(function()\n" +
			"  coroutine.yield('nope')\n" +
			"  return 'bad'\n" +
			"end)\n" +
			"local co = coroutine.create(function()\n" +
			"  local ok, err = pcall(f)\n" +
			"  return err\n" +
			"end)\n" +
			"local ok, errmsg = coroutine.resume(co)\n" +
			"return errmsg", "test").checkfunction();
		Varargs result = func.call();
		String msg = result.tojstring();
		assertTrue("Got: " + msg, msg.contains("sync-compiled boundary"));
	}

	public void testSyncInCoroutineNoYield() {
		LuaValue func = state.load(
			"local fast = nova.sync(function(x) return x + 100 end)\n" +
			"local co = coroutine.create(function()\n" +
			"  local a = fast(1)\n" +
			"  coroutine.yield(a)\n" +
			"  return fast(2)\n" +
			"end)\n" +
			"local ok1, v1 = coroutine.resume(co)\n" +
			"assert(ok1)\n" +
			"local ok2, v2 = coroutine.resume(co)\n" +
			"assert(ok2)\n" +
			"return v1, v2", "test").checkfunction();
		Varargs result = func.invoke(LuaValue.NONE);
		assertEquals(101, result.arg(1).toint());
		assertEquals(102, result.arg(2).toint());
	}

	public void testNestedSyncFunctions() {
		LuaValue func = state.load(
			"local inner = nova.sync(function(a) return a * 2 end)\n" +
			"local outer = nova.sync(function(b) return inner(b) + 1 end)\n" +
			"return outer(5)", "test").checkfunction();
		assertEquals(11, func.call().toint());
	}

	public void testReadWriteUpvalue() {
		LuaValue loader = state.load(
			"local counter = 0\n" +
			"local f = function()\n" +
			"  counter = counter + 1\n" +
			"  return counter\n" +
			"end\n" +
			"return nova.sync(f)", "test").checkfunction();
		LuaValue fn = loader.call();
		assertEquals(1, fn.call().toint());
		assertEquals(2, fn.call().toint());
		assertEquals(3, fn.call().toint());
	}

	public void testSyncFromCoroutine() {
		LuaValue fn = sync("function(x) return x * x end");
		LuaValue result = fn.call(LuaValue.valueOf(6));
		assertEquals(36, result.toint());
	}

	public void testCapturedLocalInYieldCoroutine() {
		LuaValue func = state.load(
			"local function maker(args)\n" +
			"  return nova.sync(function()\n" +
			"    return args[1] + args[2]\n" +
			"  end)\n" +
			"end\n" +
			"local co = coroutine.create(function()\n" +
			"  local heavy = maker({10, 20})\n" +
			"  local r = heavy()\n" +
			"  coroutine.yield('mid')\n" +
			"  return r\n" +
			"end)\n" +
			"local ok1, v1 = coroutine.resume(co)\n" +
			"assert(ok1)\n" +
			"local ok2, v2 = coroutine.resume(co)\n" +
			"assert(ok2)\n" +
			"return v1, v2", "test").checkfunction();
		Varargs result = func.invoke(LuaValue.NONE);
		assertEquals("mid", result.arg(1).tojstring());
		assertEquals(30, result.arg(2).toint());
	}
}
