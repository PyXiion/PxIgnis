package ru.pyxiion.luanova.vm2;

public class GlobalRegistry {
	private final LuaTable table = new LuaTable();

	public LuaTable getTable() {
		return table;
	}

	public LuaTable getSubTable(LuaString key) {
		LuaValue v = table.rawget(key);
		if (v instanceof LuaTable t) return t;
		LuaTable sub = new LuaTable();
		table.rawset(key, sub);
		return sub;
	}
}
