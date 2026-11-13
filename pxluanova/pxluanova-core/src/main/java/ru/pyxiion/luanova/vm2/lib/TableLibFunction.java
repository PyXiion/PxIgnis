package ru.pyxiion.luanova.vm2.lib;

import ru.pyxiion.luanova.vm2.LuaValue;

class TableLibFunction extends LibFunction {
	public LuaValue call() {
		return argerror(1, "table expected, got no value");
	}
}
