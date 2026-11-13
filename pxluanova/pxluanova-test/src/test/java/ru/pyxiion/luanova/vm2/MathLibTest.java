package ru.pyxiion.luanova.vm2;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform;

public class MathLibTest extends TestCase {

	private LuaValue math;

	public void setUp() {
		math = JsePlatform.standardGlobals().get("math");
	}
	
	public void testMathDPow() {
		assertEquals( 1, math.get("pow").call(LuaValue.valueOf(2), LuaValue.valueOf(0)).todouble(), 0 );
		assertEquals( 2, math.get("pow").call(LuaValue.valueOf(2), LuaValue.valueOf(1)).todouble(), 0 );
		assertEquals( 8, math.get("pow").call(LuaValue.valueOf(2), LuaValue.valueOf(3)).todouble(), 0 );
		assertEquals( -8, math.get("pow").call(LuaValue.valueOf(-2), LuaValue.valueOf(3)).todouble(), 0 );
		assertEquals( 1./8, math.get("pow").call(LuaValue.valueOf(2), LuaValue.valueOf(-3)).todouble(), 0 );
		assertEquals( 16, math.get("pow").call(LuaValue.valueOf(256), LuaValue.valueOf(.5)).todouble(), 0 );
		assertEquals( 4, math.get("pow").call(LuaValue.valueOf(256), LuaValue.valueOf(.25)).todouble(), 0 );
		assertEquals( 64, math.get("pow").call(LuaValue.valueOf(256), LuaValue.valueOf(.75)).todouble(), 0 );
	}
	
	public void testAbs() {
		assertEquals( 23.45, math.get("abs").call(LuaValue.valueOf(23.45)).todouble(), 1e-10 ); 
		assertEquals( 23.45, math.get("abs").call(LuaValue.valueOf(-23.45)).todouble(), 1e-10 ); 
	}

	public void testSqrt() {
		assertEquals( 0.0, math.get("sqrt").call(LuaValue.valueOf(0)).todouble(), 1e-10 );
		assertEquals( 1.0, math.get("sqrt").call(LuaValue.valueOf(1)).todouble(), 1e-10 );
		assertEquals( 3.0, math.get("sqrt").call(LuaValue.valueOf(9)).todouble(), 1e-10 );
		assertEquals( 10.0, math.get("sqrt").call(LuaValue.valueOf(100)).todouble(), 1e-10 );
	}

	public void testExp() {
		assertEquals( 1.0, math.get("exp").call(LuaValue.valueOf(0)).todouble(), 1e-10 );
		assertEquals( Math.E, math.get("exp").call(LuaValue.valueOf(1)).todouble(), 1e-10 );
	}

	public void testLog() {
		assertEquals( 0.0, math.get("log").call(LuaValue.valueOf(1)).todouble(), 1e-10 );
		assertEquals( 1.0, math.get("log").call(LuaValue.valueOf(Math.E)).todouble(), 1e-10 );
	}

	public void testSin() {
		assertEquals( 0.0, math.get("sin").call(LuaValue.valueOf(0)).todouble(), 1e-10 );
		assertEquals( 1.0, math.get("sin").call(LuaValue.valueOf(Math.PI/2)).todouble(), 1e-6 );
	}

	public void testCos() {
		assertEquals( 1.0, math.get("cos").call(LuaValue.valueOf(0)).todouble(), 1e-10 );
		assertEquals( 0.0, math.get("cos").call(LuaValue.valueOf(Math.PI/2)).todouble(), 1e-6 );
	}

	public void testAtan2() {
		assertEquals( Math.PI/4, math.get("atan2").call(LuaValue.valueOf(1), LuaValue.valueOf(1)).todouble(), 1e-6 );
	}
}