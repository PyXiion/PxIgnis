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

	/** Frame was entered through pcall/xpcall: errors unwind to it and its results get a leading true. */
	boolean protectedCall;
	/** The thread's errorfunc before this protected frame replaced it. */
	LuaValue savedErrorFunc;
}
