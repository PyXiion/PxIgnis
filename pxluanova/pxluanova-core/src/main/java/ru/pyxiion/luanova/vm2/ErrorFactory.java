package ru.pyxiion.luanova.vm2;

public final class ErrorFactory {
	public static final LuaString NAME = LuaValue.valueOf("__name");

	private ErrorFactory() {
	}

	public static LuaString typeName(LuaValue value) {
		if (value instanceof LuaTable || value instanceof LuaUserdata) {
			LuaValue mt = value.getmetatable();
			if (mt instanceof LuaTable metatable) {
				LuaValue name = metatable.rawget(NAME);
				if (name instanceof LuaString s) return s;
			}
		}

		return LuaString.valueOf(value.typename());
	}

	public static LuaError argError(LuaValue value, String expected) {
		return new LuaError("bad argument (" + expected + " expected, got " + typeName(value) + ")");
	}

	public static LuaError argError(int iarg, String msg) {
		return new LuaError("bad argument #" + iarg + " (" + msg + ")");
	}

	public static LuaError typeError(LuaValue value, String expected) {
		return new LuaError(expected + " expected, got " + typeName(value));
	}

	public static LuaError operandError(LuaValue operand, String verb) {
		return new LuaError("attempt to " + verb + " a " + typeName(operand) + " value");
	}

	public static LuaError compareError(LuaValue lhs, LuaValue rhs) {
		LuaString lhsType = typeName(lhs), rhsType = typeName(rhs);
		if (lhsType.equals(rhsType)) {
			return new LuaError("attempt to compare two " + lhsType + " values");
		} else {
			return new LuaError("attempt to compare " + lhsType + " with " + rhsType);
		}
	}
}
