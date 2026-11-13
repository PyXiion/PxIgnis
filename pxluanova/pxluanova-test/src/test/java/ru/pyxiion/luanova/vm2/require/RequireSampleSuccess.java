package ru.pyxiion.luanova.vm2.require;

import ru.pyxiion.luanova.vm2.LuaValue;
import ru.pyxiion.luanova.vm2.lib.TwoArgFunction;

/**
 * This should succeed as a library that can be loaded dynamically via "require()"
 */
public class RequireSampleSuccess extends TwoArgFunction {
	
	public RequireSampleSuccess() {		
	}
	
	public LuaValue call(LuaValue modname, LuaValue env) {
		return LuaValue.valueOf("require-sample-success-"+modname.tojstring());
	}	
}
