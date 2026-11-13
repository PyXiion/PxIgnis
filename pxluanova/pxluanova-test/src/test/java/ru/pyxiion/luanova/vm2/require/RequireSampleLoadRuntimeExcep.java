package ru.pyxiion.luanova.vm2.require;

import ru.pyxiion.luanova.vm2.LuaValue;
import ru.pyxiion.luanova.vm2.lib.ZeroArgFunction;

/**
* This should fail while trying to load via "require()" because it throws a RuntimeException
 * 
 */
public class RequireSampleLoadRuntimeExcep extends ZeroArgFunction {
	
	public RequireSampleLoadRuntimeExcep() {		
	}
	
	public LuaValue call() {
		throw new RuntimeException("sample-load-runtime-exception");
	}	
}
