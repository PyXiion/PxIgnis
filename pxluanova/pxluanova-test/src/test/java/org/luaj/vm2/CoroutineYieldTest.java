package org.luaj.vm2;

import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Where a coroutine may yield, and that it resumes exactly where it left off. */
public class CoroutineYieldTest extends TestCase {

	private LuaState state;

	protected void setUp() {
		state = JsePlatform.standardState();
	}

	/** Resumes {@code script} with each value in turn; returns "ok/err:value..." for every resume. */
	private List<String> run(String script, String... resumes) {
		LuaThread t = new LuaThread(state, state.load(script, "test"));
		List<String> out = new ArrayList<>();
		for (String r : resumes) {
			Varargs v = t.resume(r == null ? LuaValue.NONE : LuaValue.valueOf(r));
			StringBuilder sb = new StringBuilder(v.arg1().toboolean() ? "ok" : "err");
			for (int i = 2; i <= v.narg(); i++)
				sb.append(':').append(v.arg(i).tojstring());
			out.add(sb.toString());
		}
		out.add(t.getStatus());
		return out;
	}

	private static void assertRun(List<String> actual, String... expected) {
		assertEquals(List.of(expected), actual);
	}

	public void testYieldInsidePcall() {
		assertRun(run(
			"local function f(x) local a = coroutine.yield(x) local b = coroutine.yield(a) return 'done:' .. b end\n" +
			"return pcall(f, ...)", "foo", "bar", "baz"),
			"ok:foo", "ok:bar", "ok:true:done:baz", "dead");
	}

	public void testErrorAfterYieldInsidePcallIsCaught() {
		assertRun(run(
			"local ok, e = pcall(function() coroutine.yield(1) error('boom') end)\n" +
			"return ok, e", null, null),
			"ok:1", "ok:false:test:1: boom", "dead");
	}

	public void testNestedPcallWithYields() {
		assertRun(run(
			"return pcall(function() return pcall(function()\n" +
			"  local a = coroutine.yield(1) local b = coroutine.yield(a) return b end) end)",
			"foo", "bar", "baz"),
			"ok:1", "ok:bar", "ok:true:true:baz", "dead");
	}

	public void testPcallInTailPositionKeepsCallerLocals() {
		assertRun(run(
			"local function g() return pcall(coroutine.yield, 'in') end\n" +
			"local keep = 'kept' local ok, v = g() return keep, ok, v", null, "resumed"),
			"ok:in", "ok:kept:true:resumed", "dead");
	}

	public void testXpcallHandlerAfterYield() {
		assertRun(run(
			"return xpcall(function() coroutine.yield(1) error({code = 7}) end,\n" +
			"  function(e) return 'handled ' .. e.code end)", null, null),
			"ok:1", "ok:false:handled 7", "dead");
	}

	public void testYieldingJavaFunctionInsidePcall() {
		state.globals.set("wait", new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				return state.yield(args);
			}
		});
		assertRun(run(
			"local ok, r = pcall(function() local v = wait('w') return v .. '!' end)\n" +
			"local after = 'after'\n" +
			"return ok, r, after", null, "x"),
			"ok:w", "ok:true:x!:after", "dead");
	}

	public void testLocalAfterYieldIsNotClobbered() {
		// Resume values used to be copied one register too far, overwriting the next local.
		assertRun(run(
			"local a, b = 1, 2\n" +
			"local function f() local x = coroutine.yield() local y = 'y' return x, y end\n" +
			"local r1, r2 = f() return a, b, r1, r2", null, "x"),
			"ok", "ok:1:2:x:y", "dead");
	}

	public void testYieldFromJavaIterator() {
		state.globals.set("iter", new VarArgFunction() {
			int i = 0;
			public Varargs invoke(Varargs args) {
				if (++i > 2) return NIL;
				state.yield(valueOf(i));
				return NONE; // resume values become the loop variables
			}
		});
		assertRun(run(
			"local seen = '' for v in iter do seen = seen .. v end return seen", null, "a", "b"),
			"ok:1", "ok:2", "ok:ab", "dead");
	}

	public void testJavaFunctionAsCoroutineBody() {
		LuaThread t = new LuaThread(state, new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				return varargsOf(valueOf("got"), args.arg1());
			}
		});
		Varargs v = t.resume(LuaValue.valueOf("x"));
		assertTrue(v.arg1().toboolean());
		assertEquals("got", v.arg(2).tojstring());
		assertEquals("x", v.arg(3).tojstring());
		assertEquals("dead", t.getStatus());
	}

	public void testCoroutineWrapOfJavaYield() {
		assertRun(run(
			"local co = coroutine.wrap(coroutine.yield) local a = co(1) return a", (String) null),
			"ok:1", "dead");
	}

	public void testYieldInsideMetamethodIsAnError() {
		List<String> r = run(
			"local t = setmetatable({}, {__index = function(t, k) return coroutine.yield(k) end})\n" +
			"return t.foo", (String) null);
		assertTrue(r.toString(), r.get(0).startsWith("err:") && r.get(0).contains("attempt to yield across a C-call boundary"));
		assertEquals("dead", r.get(1));
	}

	public void testYieldInsideSortComparatorIsAnError() {
		List<String> r = run(
			"local t = {3, 1, 2} table.sort(t, function(a, b) coroutine.yield() return a < b end)", (String) null);
		assertTrue(r.toString(), r.get(0).contains("attempt to yield across a C-call boundary"));
	}

	public void testPcallCatchesYieldAcrossBoundary() {
		assertRun(run(
			"local t = setmetatable({}, {__index = function() coroutine.yield() end})\n" +
			"local ok, e = pcall(function() return t.x end) return ok, (e:gsub('^.-: ', ''))", (String) null),
			"ok:false:attempt to yield across a C-call boundary", "dead");
	}

	public void testResumerStatusIsRunningAgain() {
		assertRun(run(
			"local me = coroutine.running()\n" +
			"coroutine.resume(coroutine.create(function() end))\n" +
			"return coroutine.status(me)", (String) null),
			"ok:running", "dead");
	}
}
