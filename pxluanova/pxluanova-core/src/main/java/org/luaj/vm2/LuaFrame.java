package org.luaj.vm2;

class LuaFrame {
	LuaClosure closure;
	LuaValue[] stack;
	int pc;
	int top;
	Varargs v = LuaValue.NONE;
	UpValue[] openups;
	Varargs varargs;

	int callerOp;
	int callerA;
	int callerB;
	int callerC;

	LuaValue storedFunc;
	Varargs storedCallArgs;
	Object storedContinuation;
}
