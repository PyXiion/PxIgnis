package ru.pyxiion.luanova.vm2.lib;

import ru.pyxiion.luanova.vm2.Buffer;
import ru.pyxiion.luanova.vm2.LuaBoolean;
import ru.pyxiion.luanova.vm2.LuaInteger;
import ru.pyxiion.luanova.vm2.LuaNumber;
import ru.pyxiion.luanova.vm2.LuaString;
import ru.pyxiion.luanova.vm2.LuaTable;
import ru.pyxiion.luanova.vm2.LuaValue;
import ru.pyxiion.luanova.vm2.Varargs;

public class Utf8Lib extends TwoArgFunction {

	private static final int[] LIMITS = {0xFF, 0x7F, 0x7FF, 0xFFFF};
	public static final long MAX_UNICODE = 0x10FFFFL;

	private static final LuaString PATTERN = LuaString.valueOf(new byte[]{
		'[', 0x00, '-', 0x7f, (byte) 0xc2, '-', (byte) 0xf4, ']', '[', (byte) 0x80, '-', (byte) 0xbf, ']', '*',
	});

	private codesIter iter;

	public Utf8Lib() {
		iter = new codesIter();
	}

	public LuaValue call(LuaValue modname, LuaValue env) {
		LuaTable utf8 = new LuaTable(0, 7);
		utf8.set("char", new char_());
		utf8.set("codes", new codes());
		utf8.set("codepoint", new codepoint());
		utf8.set("len", new len());
		utf8.set("offset", new offset());
		env.set("utf8", utf8);

		LuaValue pattern = PATTERN;
		utf8.set("charpattern", pattern);

		if (!env.get("package").isnil()) env.get("package").get("loaded").set("utf8", utf8);

		return utf8;
	}

	static class char_ extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			Buffer sb = new Buffer(args.narg());
			byte[] buffer = null;
			for (int i = 1, n = args.narg(); i <= n; i++) {
				int codepoint = args.checkint(i);
				if (codepoint < 0 || codepoint > MAX_UNICODE) {
					argerror(i, "value out of range");
				}

				if (codepoint < 0x80) {
					sb.append((byte) codepoint);
				} else {
					if (buffer == null) buffer = new byte[8];
					int j = buildCharacter(buffer, codepoint);
					for (int k = 8 - j; k < 8; k++) {
						sb.append(buffer[k]);
					}
				}
			}

			return sb.tostring();
		}
	}

	class codes extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			return varargsOf(iter, args.checkstring(1), valueOf(0));
		}
	}

	class codesIter extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			LuaString s = args.checkstring(1);
			int idx = args.checkint(2) - 1;
			int[] off = new int[1];
			if (idx < 0) {
				idx = 0;
			} else if (idx < s.length()) {
				idx++;
				while (isCont(s, idx)) idx++;
			}
			if (idx >= s.length()) {
				return NONE;
			} else {
				long codepoint = decodeUtf8(s, idx, off);
				if (codepoint == -1 || isCont(s, idx + off[0])) error("invalid UTF-8 code");
				return varargsOf(valueOf(idx + 1), LuaInteger.valueOf(codepoint));
			}
		}
	}

	static class codepoint extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			LuaString s = args.checkstring(1);
			int length = s.length();
			int i = posRelative(args.optint(2, 1), length);
			int j = posRelative(args.optint(3, i), length);

			if (i < 1) argerror(2, "out of bounds");
			if (j > length) argerror(3, "out of bounds");
			if (i > j) return NONE;

			int[] off = new int[1];
			int n = 0;
			LuaNumber[] codepoints = new LuaNumber[j - i + 1];

			do {
				long codepoint = decodeUtf8(s, i - 1, off);
				if (codepoint < 0) error("invalid UTF-8 code");
				codepoints[n++] = LuaInteger.valueOf(codepoint);
			} while ((i += off[0]) <= j);

			return varargsOf(codepoints, 0, n);
		}
	}

	static class len extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			LuaString s = args.checkstring(1);
			int slen = s.length();
			int i = posRelative(args.optint(2, 1), slen) - 1;
			int j = posRelative(args.optint(3, -1), slen) - 1;

			if (i < 0 || i > slen) argerror(2, "initial position out of string");
			if (j >= slen) argerror(3, "final position out of string");

			int n = 0;
			int[] off = new int[1];
			while (i <= j) {
				long codepoint = decodeUtf8(s, i, off);
				if (codepoint < 0) return varargsOf(NIL, valueOf(i + 1));

				n++;
				i += off[0];
			}

			return valueOf(n);
		}
	}

	static class offset extends VarArgFunction {
		public Varargs invoke(Varargs args) {
			LuaString s = args.checkstring(1);
			int n = args.checkint(2);

			int length = s.length();
			int position = (n >= 0) ? 1 : length + 1;
			position = posRelative(args.optint(3, position), length) - 1;
			if (position < 0 || position > length) argerror(3, "position out of bounds");

			if (n == 0) {
				while (position > 0 && isCont(s, position)) position--;
			} else {
				if (isCont(s, position)) error("initial position is a continuation byte");

				if (n < 0) {
					while (n < 0 && position > 0) {
						do {
							position--;
						} while (position > 0 && isCont(s, position));
						n++;
					}
				} else {
					n--;
					while (n > 0 && position < length) {
						do {
							position++;
						} while (isCont(s, position));
						n--;
					}
				}
			}

			if (n != 0) return NIL;

			if (isCont(s, position)) error("initial position is a continuation byte");

			int endPosition = position;
			if (position < s.length() && (s.luaByte(position) & 0x80) != 0) {
				while (isCont(s, endPosition + 1)) endPosition++;
			}

			return varargsOf(valueOf(position + 1), valueOf(endPosition + 1));
		}
	}

	static int buildCharacter(byte[] buffer, long codepoint) {
		int mfb = 0x3f;
		int j = 1;
		do {
			buffer[8 - j++] = ((byte) (0x80 | (codepoint & 0x3f)));
			codepoint >>= 6;
			mfb >>= 1;
		} while (codepoint > mfb);
		buffer[8 - j] = (byte) ((~mfb << 1) | codepoint);
		return j;
	}

	static long decodeUtf8(LuaString str, int index, int[] offset) {
		int first = str.luaByte(index);
		if (first < 0x80) {
			offset[0] = 1;
			return first;
		}

		int count = 0;
		long result = 0;
		int length = str.length();
		while ((first & 0x40) != 0) {
			index++;
			if (index >= length) return -1;

			int cc = str.luaByte(index);
			if ((cc & 0xC0) != 0x80) return -1;

			count++;
			result = (result << 6) | (cc & 0x3F);
			first <<= 1;
		}

		result |= ((first & 0x7F)) << (count * 5);
		if (count > 3 || result > MAX_UNICODE || result <= LIMITS[count]) return -1;
		offset[0] = count + 1;
		return result;
	}

	static int posRelative(int pos, int len) {
		return pos >= 0 ? pos : len + pos + 1;
	}

	static boolean isCont(LuaString s, int idx) {
		return idx < s.length() && (s.luaByte(idx) & 0xC0) == 0x80;
	}

	static Varargs varargsOf(LuaNumber[] a, int start, int length) {
		LuaValue[] v = new LuaValue[length];
		System.arraycopy(a, start, v, 0, length);
		return LuaValue.varargsOf(v);
	}
}
