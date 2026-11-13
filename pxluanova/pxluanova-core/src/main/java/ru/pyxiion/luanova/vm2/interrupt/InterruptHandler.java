package ru.pyxiion.luanova.vm2.interrupt;

import ru.pyxiion.luanova.vm2.LuaError;

public interface InterruptHandler {
	InterruptAction interrupted() throws LuaError;
}
