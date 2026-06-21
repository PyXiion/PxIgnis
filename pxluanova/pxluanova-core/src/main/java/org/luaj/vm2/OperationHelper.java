package org.luaj.vm2;

public final class OperationHelper {
	private OperationHelper() {
	}

	public static LuaValue add(LuaValue left, LuaValue right) throws LuaError {
		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(dLeft + dRight);
		} else {
			return arithMetatable(LuaValue.ADD, left, right);
		}
	}

	public static LuaValue sub(LuaValue left, LuaValue right) throws LuaError {
		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(dLeft - dRight);
		} else {
			return arithMetatable(LuaValue.SUB, left, right);
		}
	}

	public static LuaValue mul(LuaValue left, LuaValue right) throws LuaError {
		if (left instanceof LuaInteger l && right instanceof LuaInteger r) {
			return LuaInteger.valueOf((long) l.v * (long) r.v);
		}

		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(dLeft * dRight);
		} else {
			return arithMetatable(LuaValue.MUL, left, right);
		}
	}

	public static LuaValue div(LuaValue left, LuaValue right) throws LuaError {
		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(div(dLeft, dRight));
		} else {
			return arithMetatable(LuaValue.DIV, left, right);
		}
	}

	public static LuaValue mod(LuaValue left, LuaValue right) throws LuaError {
		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(mod(dLeft, dRight));
		} else {
			return arithMetatable(LuaValue.MOD, left, right);
		}
	}

	public static LuaValue pow(LuaValue left, LuaValue right) throws LuaError {
		double dLeft, dRight;
		if (checkNumber(left, dLeft = left.todouble()) && checkNumber(right, dRight = right.todouble())) {
			return LuaDouble.valueOf(Math.pow(dLeft, dRight));
		} else {
			return arithMetatable(LuaValue.POW, left, right);
		}
	}

	public static double div(double lhs, double rhs) {
		return rhs != 0 ? lhs / rhs : lhs > 0 ? Double.POSITIVE_INFINITY : lhs == 0 ? Double.NaN : Double.NEGATIVE_INFINITY;
	}

	public static double mod(double lhs, double rhs) {
		double mod = lhs % rhs;
		return mod * rhs < 0 ? mod + rhs : mod;
	}

	private static LuaValue arithMetatable(LuaValue tag, LuaValue left, LuaValue right) throws LuaError {
		LuaValue h = left.metatag(tag);
		if (!h.isnil()) return h.call(left, right);

		h = right.metatag(tag);
		if (!h.isnil()) return h.call(right, left);

		throw createArithmeticError(left, right);
	}

	private static LuaError createArithmeticError(LuaValue left, LuaValue right) {
		LuaValue value;
		if (!left.isnumber()) {
			value = left;
		} else {
			value = right;
		}
		return ErrorFactory.operandError(value, "perform arithmetic on");
	}

	public static LuaValue concat(LuaValue left, LuaValue right) throws LuaError {
		if (left.isstring() && right.isstring()) {
			return right.strvalue().concatTo(left.strvalue());
		}

		LuaValue h = left.metatag(LuaValue.CONCAT);
		if (h.isnil()) h = right.metatag(LuaValue.CONCAT);
		if (!h.isnil()) return h.call(left, right);

		throw ErrorFactory.operandError(left.isstring() ? right : left, "concatenate");
	}

	public static boolean lt(LuaValue left, LuaValue right) throws LuaError {
		int tLeft = left.type(), tRight = right.type();

		if (tLeft == LuaValue.TNUMBER && tRight == LuaValue.TNUMBER) return left.todouble() < right.todouble();
		if (tLeft == LuaValue.TSTRING && tRight == LuaValue.TSTRING) return left.strvalue().strcmp(right.strvalue()) < 0;

		LuaValue mt = left.metatag(LuaValue.LT);
		if (mt.isnil()) mt = right.metatag(LuaValue.LT);
		if (mt.isnil()) throw ErrorFactory.compareError(left, right);

		return mt.call(left, right).toboolean();
	}

	public static boolean le(LuaValue left, LuaValue right) throws LuaError {
		int tLeft = left.type(), tRight = right.type();

		if (tLeft == LuaValue.TNUMBER && tRight == LuaValue.TNUMBER) return left.todouble() <= right.todouble();
		if (tLeft == LuaValue.TSTRING && tRight == LuaValue.TSTRING) return left.strvalue().strcmp(right.strvalue()) <= 0;

		LuaValue leMt = left.metatag(LuaValue.LE);
		if (!leMt.isnil()) return leMt.call(left, right).toboolean();

		LuaValue ltMt = left.metatag(LuaValue.LT);
		if (ltMt.isnil()) ltMt = right.metatag(LuaValue.LT);
		if (ltMt.isnil()) throw ErrorFactory.compareError(left, right);

		return !ltMt.call(right, left).toboolean();
	}

	public static boolean eq(LuaValue left, LuaValue right) throws LuaError {
		int tLeft = left.type();
		if (tLeft != right.type()) return false;

		switch (tLeft) {
		case LuaValue.TNIL: return true;
		case LuaValue.TNUMBER: return left.todouble() == right.todouble();
		case LuaValue.TBOOLEAN: return left.toboolean() == right.toboolean();
		case LuaValue.TSTRING: return left == right || left.equals(right);
		case LuaValue.TUSERDATA:
		case LuaValue.TTABLE: {
			if (left == right || left.equals(right)) return true;

			LuaValue leftMeta = left.getmetatable();
			if (leftMeta == null) return false;

			LuaValue rightMeta = right.getmetatable();
			if (rightMeta == null) return false;

			LuaValue h = leftMeta.rawget(LuaValue.EQ);
			return !(h.isnil() || h != rightMeta.rawget(LuaValue.EQ)) && h.call(left, right).toboolean();
		}
		default: return left == right || left.equals(right);
		}
	}

	public static LuaValue length(LuaValue value) throws LuaError {
		switch (value.type()) {
		case LuaValue.TTABLE: {
			LuaValue h = value.metatag(LuaValue.LEN);
			if (h.isnil()) {
				return LuaInteger.valueOf(((LuaTable) value).length());
			} else {
				return h.call(value);
			}
		}
		case LuaValue.TSTRING:
			return LuaInteger.valueOf(((LuaString) value).length());
		default: {
			LuaValue h = value.metatag(LuaValue.LEN);
			if (h.isnil()) throw ErrorFactory.operandError(value, "get length of");
			return h.call(value);
		}
		}
	}

	public static LuaValue neg(LuaValue value) throws LuaError {
		int type = value.type();
		if (type == LuaValue.TNUMBER) {
			if (value instanceof LuaInteger) {
				int x = ((LuaInteger) value).v;
				if (x != Integer.MIN_VALUE) return LuaInteger.valueOf(-x);
			}
			return LuaDouble.valueOf(-value.todouble());
		} else if (type == LuaValue.TSTRING) {
			double res = value.todouble();
			if (!Double.isNaN(res)) return LuaDouble.valueOf(-res);
		}

		LuaValue meta = value.metatag(LuaValue.UNM);
		if (meta.isnil()) throw ErrorFactory.operandError(value, "perform arithmetic on");

		return meta.call(value);
	}

	public static LuaValue getTable(LuaValue t, LuaValue key) throws LuaError {
		return getTable(t, key, -1);
	}

	public static LuaValue getTable(LuaValue t, LuaValue key, int stack) throws LuaError {
		LuaValue tm;
		int loop = 0;
		do {
			if (t instanceof LuaTable table) {
				LuaValue res = table.rawget(key);
				if (!res.isnil() || (tm = t.metatag(LuaValue.INDEX)).isnil()) {
					return res;
				}
			} else if ((tm = t.metatag(LuaValue.INDEX)).isnil()) {
				throw ErrorFactory.operandError(t, "index");
			}

			if (tm instanceof LuaFunction metaFunc) return metaFunc.call(t, key);

			t = tm;
			stack = -1;
		} while (++loop < 100);
		throw new LuaError("loop in gettable");
	}

	public static void setTable(LuaValue t, LuaValue key, LuaValue value) throws LuaError {
		setTable(t, key, value, -1);
	}

	public static void setTable(LuaValue t, LuaValue key, LuaValue value, int stack) throws LuaError {
		int loop = 0;
		do {
			LuaValue tm;
			if (t instanceof LuaTable table) {
				table.set(key, value);
				LuaValue found = table.rawget(key);
				if (!found.isnil() || (tm = t.metatag(LuaValue.NEWINDEX)).isnil()) return;
			} else if ((tm = t.metatag(LuaValue.NEWINDEX)).isnil()) {
				throw ErrorFactory.operandError(t, "index");
			}

			if (tm instanceof LuaFunction metaFunc) {
				metaFunc.call(t, key, value);
				return;
			}
			t = tm;
			stack = -1;
		} while (++loop < 100);
		throw new LuaError("loop in settable");
	}

	public static LuaValue toString(LuaValue value) throws LuaError {
		LuaValue h = value.metatag(LuaValue.TOSTRING);
		return h.isnil() ? toStringDirect(value) : h.call(value);
	}

	public static LuaString checkToString(LuaValue value) throws LuaError {
		if (!value.isstring()) throw new LuaError("'__tostring' must return a string");
		return (LuaString) value;
	}

	public static LuaString toStringDirect(LuaValue value) {
		LuaValue v = value.tostring();
		return v.isnil() ? LuaString.valueOf(value.toString()) : (LuaString) v;
	}

	private static boolean checkNumber(LuaValue lua, double value) {
		return lua.isnumber();
	}
}
