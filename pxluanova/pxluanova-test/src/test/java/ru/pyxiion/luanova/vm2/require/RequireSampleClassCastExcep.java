package ru.pyxiion.luanova.vm2.require;

import ru.pyxiion.luanova.vm2.LuaValue;

/**
 * This should fail while trying to load via "require() because it is not a LibFunction"
 * 
 */
public class RequireSampleClassCastExcep {
	
	public RequireSampleClassCastExcep() {		
	}
	
	public LuaValue call() {
		return LuaValue.valueOf("require-sample-class-cast-excep");
	}	
}
