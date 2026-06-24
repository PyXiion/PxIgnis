package org.luaj.vm2.compiler;

import junit.framework.TestCase;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

public class LambdaLiteralTests extends TestCase {

	private LuaState state;
	private static final String PRAGMA = "--# nova syntax\n";

	protected void setUp() throws Exception {
		super.setUp();
		state = JsePlatform.standardState();
	}

	private LuaValue run(String script) {
		LuaValue c = state.load(script, "script");
		return c.call();
	}

	/* ---------- basic lambda forms ---------- */

	public void testZeroArgBlock() {
		LuaValue r = run(PRAGMA + "return (\\{ return 1 + 1 })()");
		assertEquals(2, r.toint());
	}

	public void testZeroArgBlockWithReturn() {
		LuaValue r = run(PRAGMA + "return (\\{ return 7 * 6 })()");
		assertEquals(42, r.toint());
	}

	public void testSingleArgLambda() {
		LuaValue r = run(PRAGMA + "return (\\{ x -> return x * x })(5)");
		assertEquals(25, r.toint());
	}

	public void testMultiArgLambda() {
		LuaValue r = run(PRAGMA + "return (\\{ a, b, c -> return a + b + c })(1, 2, 3)");
		assertEquals(6, r.toint());
	}

	public void testLambdaAssignedToLocal() {
		LuaValue r = run(
			PRAGMA +
			"local f = \\{ x -> return x * 2 }\n" +
			"return f(21)"
		);
		assertEquals(42, r.toint());
	}

	public void testLambdaWithReturn() {
		LuaValue r = run(
			PRAGMA +
			"local sum = \\{ a, b, c -> return a + b + c }\n" +
			"return sum(10, 20, 30)"
		);
		assertEquals(60, r.toint());
	}

	/* ---------- trailing block sugar ---------- */

	public void testTrailingBlockSugar() {
		LuaValue r = run(
			PRAGMA +
			"local x = 0\n" +
			"(function(cb) cb() end) \\{ x = 99 }\n" +
			"return x"
		);
		assertEquals(99, r.toint());
	}

	public void testTrailingBlockWithArgs() {
		LuaValue r = run(
			PRAGMA +
			"local f = function(a, b, cb) return cb(a + b) end\n" +
			"return f(3, 4) \\{ n -> return n * n }"
		);
		assertEquals(49, r.toint());
	}

	public void testTrailingBlockCallbackIsInvoked() {
		LuaValue r = run(
			PRAGMA +
			"local sum = 0\n" +
			"(function(cb) cb(5); cb(7); cb(9) end)(function(x) sum = sum + x end)\n" +
			"return sum"
		);
		assertEquals(21, r.toint());
	}

	/* ---------- composed forms ---------- */

	public void testLambdaInsideExpression() {
		LuaValue r = run(
			PRAGMA +
			"local total = 0\n" +
			"for i = 1, 4 do\n" +
			"  total = total + (\\{ x -> return x * x })(i)\n" +
			"end\n" +
			"return total"
		);
		assertEquals(30, r.toint());
	}

	public void testEmptyBodyLambda() {
		LuaValue r = run(PRAGMA + "return (\\{ a, b -> })(1, 2)");
		assertTrue("expected nil or 0 results, got: " + r, r.isnil() || r.toint() == 0);
	}

	public void testLambdaInTableConstructor() {
		LuaValue r = run(
			PRAGMA +
			"local t = { 1, \\{ x -> return x * 2 }, \\{ return 99 } }\n" +
			"return t[1] + t[2](3) + t[3]()"
		);
		assertEquals(1 + 6 + 99, r.toint());
	}

	public void testNestedLambda() {
		LuaValue r = run(
			PRAGMA +
			"local mk = \\{ base -> return \\{ n -> return base + n } }\n" +
			"local add10 = mk(10)\n" +
			"return add10(32)"
		);
		assertEquals(42, r.toint());
	}

	public void testLambdaClosesOverLocal() {
		LuaValue r = run(
			PRAGMA +
			"local x = 40\n" +
			"local f = \\{ y -> return x + y }\n" +
			"return f(2)"
		);
		assertEquals(42, r.toint());
	}

	public void testArrowExplicitReturn() {
		/* \{ a, b -> return a + b } -- explicit return is required. */
		LuaValue r = run(PRAGMA + "local f = \\{ a, b -> return a + b }\n return f(1, 2)");
		assertEquals(3, r.toint());
	}

	/* ---------- error handling ---------- */

	public void testStrayBackslashIsError() {
		try {
			run("local x = \\n");
			fail("expected syntax error for stray backslash");
		} catch (LuaError expected) {
			String m = expected.getMessage();
			assertNotNull(m);
			assertTrue("message should mention '\\', got: " + m, m.contains("\\"));
		}
	}

	public void testStrayBackslashNotFollowedByBrace() {
		try {
			run("local x = \\x");
			fail("expected syntax error for \\x");
		} catch (LuaError expected) {
			// ok
		}
	}

	public void testDisabledByDefault() {
		try {
			run("local f = \\{ return 1 }\n return f()");
			fail("expected syntax error when lambda syntax is off");
		} catch (LuaError expected) {
			String m = expected.getMessage();
			assertTrue("error should mention --# nova syntax, got: " + m,
				m != null && m.contains("nova syntax"));
		}
	}

	public void testDisabledTrailingBlockIsError() {
		try {
			run("print('hi') \\{ return 1 }");
			fail("expected syntax error when lambda syntax is off");
		} catch (LuaError expected) {
			String m = expected.getMessage();
			assertTrue("error should mention --# nova syntax, got: " + m,
				m != null && m.contains("nova syntax"));
		}
	}

	/* ---------- arrow token ---------- */

	public void testArrowInCodeMeansLambdaArrow() {
		LuaValue r = run(PRAGMA + "return (\\{ x -> return x * 2 })(21)");
		assertEquals(42, r.toint());
	}

	public void testArrowInsideStringIsJustText() {
		LuaValue r = run(PRAGMA + "return 'a->b' .. '->'");
		assertEquals("a->b->", r.tojstring());
	}

	/* ---------- complex / integration ---------- */

	public void testMultipleTrailingBlocks() {
		LuaValue r = run(
			PRAGMA +
			"local x = 0\n" +
			"local adder = function(cb) return function(n) cb(); return n + 41 end end\n" +
			"return adder \\{ x = 1 }(1)"
		);
		assertEquals(42, r.toint());
	}

	public void testRealWorldStyle() {
		LuaValue r = run(
			PRAGMA +
			"local results = {}\n" +
			"local function register(handler) handler(1, 'hello'); handler(2, 'world') end\n" +
			"register \\{ n, msg -> results[n] = msg }\n" +
			"local sum = \\{ a, b, c -> return a + b + c }\n" +
			"local function map(t, fn)\n" +
			"  local out = {}\n" +
			"  for i, v in ipairs(t) do out[i] = fn(v) end\n" +
			"  return out\n" +
			"end\n" +
			"local double = \\{ x -> return x * 2 }\n" +
			"local nums = map({1, 2, 3}, double)\n" +
			"return results[1] .. ' ' .. results[2] .. ' ' .. sum(10, 20, 30) .. ' ' .. (nums[1] + nums[2] + nums[3])"
		);
		assertEquals("hello world 60 12", r.tojstring());
	}

	public void testLambdaInForLoop() {
		/* Lambdas inside for loops, closures over loop variables. */
		LuaValue r = run(
			PRAGMA +
			"local fns = {}\n" +
			"for i = 1, 3 do\n" +
			"  fns[i] = \\{ x -> return x + i }\n" +
			"end\n" +
			"return fns[1](10) + fns[2](100) + fns[3](1000)"
		);
		assertEquals(11 + 102 + 1003, r.toint());
	}

	public void testLambdaWithIfStatement() {
		LuaValue r = run(
			PRAGMA +
			"local f = \\{ n ->\n" +
			"  if n > 0 then return n end\n" +
			"  return -n\n" +
			"}\n" +
			"return f(5) * 100 + f(-3)"
		);
		assertEquals(500 + 3, r.toint());
	}

	public void testLambdaInTableMethod() {
		LuaValue r = run(
			PRAGMA +
			"local t = {}\n" +
			"function t:call(cb) cb(self.value) end\n" +
			"t.value = 41\n" +
			"local out = 0\n" +
			"t:call() \\{ v -> out = v + 1 }\n" +
			"return out"
		);
		assertEquals(42, r.toint());
	}

	public void testStandaloneLambdaDiscarded() {
		LuaValue r = run(
			PRAGMA +
			"local out\n" +
			"out = (\\{ x -> return x * 2 })(21)\n" +
			"return out"
		);
		assertEquals(42, r.toint());
	}

	/* ---------- pragma detection tests ---------- */

	public void testPragmaEnablesLambda() {
		/* Basic pragma on line 1 enables the extension. */
		LuaValue r = run(PRAGMA + "return (\\{ return 42 })()");
		assertEquals(42, r.toint());
	}

	public void testPragmaLeadingWhitespaceOk() {
		/* Leading tabs/spaces before the pragma still work. */
		LuaValue r = run("\t  " + PRAGMA + "return (\\{ return 7 })()");
		assertEquals(7, r.toint());
	}

	public void testPragmaWrongCaseIgnored() {
		/* Wrong case: --# NOVA SYNTAX should NOT enable the extension. */
		try {
			run("--# NOVA SYNTAX\nreturn (\\{ return 1 })()");
			fail("expected syntax error -- wrong case pragma should not enable lambda");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}

	public void testPragmaOnLineTwoIgnored() {
		/* Pragma on line 2 is not on line 1, so it must not enable the extension. */
		try {
			run("\n" + PRAGMA + "return (\\{ return 1 })()");
			fail("expected syntax error -- pragma on line 2 should not enable lambda");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}

	public void testPragmaInStringIgnored() {
		/* Pragma text inside a string literal should not enable the extension. */
		try {
			run("local s = \"--# nova syntax\"\nreturn (\\{ return 1 })()");
			fail("expected syntax error -- pragma in string should not enable lambda");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}

	public void testPragmaInBlockCommentIgnored() {
		/* Pragma inside a block comment (--[[ ]]) should not enable the extension. */
		try {
			run("--[[--# nova syntax ]]\nreturn (\\{ return 1 })()");
			fail("expected syntax error -- pragma in block comment should not enable lambda");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}

	public void testPragmaBeforeNovaOnlyCommentIgnored() {
		/* A space between -- and #: -- # nova syntax is a regular comment. */
		try {
			run("-- # nova syntax\nreturn (\\{ return 1 })()");
			fail("expected syntax error -- space after -- means no pragma detection");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}

	public void testPragmaFollowedByCode() {
		/* Pragma followed by code on the same line: the entire line after
		 * --# nova syntax is a comment, so it should still work. */
		LuaValue r = run("--# nova syntax\n" +
			"return (\\{ return 42 })()");
		assertEquals(42, r.toint());
	}

	public void testEmptyScriptWithPragma() {
		/* An empty script with just the pragma should not crash. */
		LuaValue r = run(PRAGMA);
		assertTrue(r.isnil());
	}

	public void testNoPragmaNoLambda() {
		/* A script with no pragma line must not accept lambda syntax. */
		try {
			run("local x = \\{ return 1 }");
			fail("expected syntax error without pragma");
		} catch (LuaError expected) {
			assertTrue("expected lambda-related error, got: " + expected.getMessage(),
				expected.getMessage().contains("lambda"));
		}
	}
}
