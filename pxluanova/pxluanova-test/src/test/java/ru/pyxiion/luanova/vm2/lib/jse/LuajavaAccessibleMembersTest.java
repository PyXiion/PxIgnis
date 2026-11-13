package ru.pyxiion.luanova.vm2.lib.jse;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.LuaState;
import ru.pyxiion.luanova.vm2.LuaValue;

public class LuajavaAccessibleMembersTest extends TestCase {
		
	private LuaState state;

    protected void setUp() throws Exception {
        super.setUp();
        state = JsePlatform.standardState();
     }

    private String invokeScript(String script) {
    	try {
			LuaValue c = state.load(script, "script");
			return c.call().tojstring();
    	} catch ( Exception e ) {
    		fail("exception: "+e );
    		return "failed";
    	}
    }
    
	public void testAccessFromPrivateClassImplementedMethod() {
		assertEquals("privateImpl-aaa-interface_method(bar)", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"a = b:create_PrivateImpl('aaa');" +
			"return a:interface_method('bar');"));
	}

	public void testAccessFromPrivateClassPublicMethod() {
		assertEquals("privateImpl-aaa-public_method", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"a = b:create_PrivateImpl('aaa');" +
			"return a:public_method();"));
	}

	public void testAccessFromPrivateClassGetPublicField() {
		assertEquals("aaa", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"a = b:create_PrivateImpl('aaa');" +
			"return a.public_field;"));
	}

	public void testAccessFromPrivateClassSetPublicField() {
		assertEquals("foo", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"a = b:create_PrivateImpl('aaa');" +
			"a.public_field = 'foo';" +
			"return a.public_field;"));
	}

	public void testAccessFromPrivateClassPublicConstructor() {
		assertEquals("privateImpl-constructor", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"c = b:get_PrivateImplClass();" +
			"return luajava.new(c);"));
	}

	public void testAccessPublicEnum() {
		assertEquals("class ru.pyxiion.luanova.vm2.lib.jse.TestClass$SomeEnum", invokeScript(
			"b = luajava.newInstance('"+TestClass.class.getName()+"');" +
			"return b.SomeEnum"));
	}
}
