package org.luaj.vm2;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Copies Lua values from one {@link LuaState} to another, for states that run on different threads and must
 * not share mutable objects.
 * <p>
 * {@link #snapshot} runs on the thread that owns the source state and turns values into a state-independent,
 * never-exposed form; {@link #materialize} runs on the thread that owns the target state and rebuilds them there.
 * <ul>
 * <li>nil, booleans, numbers and strings are immutable and pass as they are;</li>
 * <li>tables (with their metatables) are deep-copied, keeping cycles and shared references;</li>
 * <li>Lua functions are rebuilt from their (immutable) prototype, with their upvalues copied; an upvalue
 *     holding the source globals becomes the target globals, and upvalues shared between functions stay shared;</li>
 * <li>library functions and tables (e.g. {@code math.floor}, a {@code require}d module) are looked up by path in
 *     the target, so {@code local floor = math.floor} keeps working;</li>
 * <li>userdata goes through the {@link UserdataPolicy};</li>
 * <li>anything else (coroutines, other Java functions) is an error naming where the value was found.</li>
 * </ul>
 */
public final class LuaTransfer {

	/** Decides how userdata crosses states. */
	public interface UserdataPolicy {
		/** Returns a state-independent form of {@code u}, or throws {@link LuaError} if it cannot be sent. */
		Object export(LuaUserdata u);

		/** Rebuilds a value produced by {@link #export} in {@code target}. */
		LuaValue materialize(Object exported, LuaState target);
	}

	/** Refuses every userdata. */
	public static final UserdataPolicy NO_USERDATA = new UserdataPolicy() {
		public Object export(LuaUserdata u) {
			throw new LuaError("userdata cannot be sent to another Lua state");
		}

		public LuaValue materialize(Object exported, LuaState target) {
			throw new IllegalStateException();
		}
	};

	private LuaTransfer() {
	}

	/** A state-independent copy of some values. Opaque; only {@link #materialize} reads it. */
	public static final class Snapshot {
		private final Object[] values;

		private Snapshot(Object[] values) {
			this.values = values;
		}
	}

	public static Snapshot snapshot(Varargs values, LuaState from, UserdataPolicy policy) {
		return snapshot(values, from, policy, i -> i == 1 ? "value" : "value #" + i);
	}

	/** @param label names value {@code i} (1-based) in error messages, e.g. "argument #2". */
	public static Snapshot snapshot(Varargs values, LuaState from, UserdataPolicy policy, IntFunction<String> label) {
		Exporter e = new Exporter(from, policy);
		Object[] out = new Object[values.narg()];
		for (int i = 0; i < out.length; i++)
			out[i] = e.export(values.arg(i + 1), label.apply(i + 1));
		return new Snapshot(out);
	}

	public static Varargs materialize(Snapshot snapshot, LuaState to, UserdataPolicy policy) {
		Importer im = new Importer(to, policy);
		LuaValue[] out = new LuaValue[snapshot.values.length];
		for (int i = 0; i < out.length; i++)
			out[i] = im.materialize(snapshot.values[i]);
		return LuaValue.varargsOf(out);
	}

	// ---- snapshot form -------------------------------------------------------------------------------

	private static final class TableSnap {
		final List<Object> keys = new ArrayList<>();
		final List<Object> values = new ArrayList<>();
		Object metatable;
	}

	private static final class ClosureSnap {
		final Prototype p;
		final UpvalSnap[] upvalues;

		ClosureSnap(Prototype p, int n) {
			this.p = p;
			this.upvalues = new UpvalSnap[n];
		}
	}

	private static final class UpvalSnap {
		boolean isGlobals;
		Object value;
	}

	private static final class LibRef {
		final String path;

		LibRef(String path) {
			this.path = path;
		}
	}

	private static final class UserdataSnap {
		final Object exported;

		UserdataSnap(Object exported) {
			this.exported = exported;
		}
	}

	/** Prefix of paths into package.loaded; the module name is followed by {@link #SEP} (names may contain dots). */
	private static final String LOADED = "package.loaded:";
	private static final char SEP = '\u0001';

	// ---- export --------------------------------------------------------------------------------------

	private static final class Exporter {
		final LuaState from;
		final UserdataPolicy policy;
		final IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
		IdentityHashMap<LuaValue, String> libraries;

		Exporter(LuaState from, UserdataPolicy policy) {
			this.from = from;
			this.policy = policy;
		}

		Object export(LuaValue v, String where) {
			switch (v.type()) {
				case LuaValue.TNIL:
				case LuaValue.TBOOLEAN:
				case LuaValue.TNUMBER:
				case LuaValue.TSTRING:
					return v;
				case LuaValue.TTABLE: {
					String lib = libraryPath(v);
					if (lib != null)
						return new LibRef(lib);
					Object done = seen.get(v);
					if (done != null)
						return done;
					LuaTable t = (LuaTable) v;
					TableSnap snap = new TableSnap();
					seen.put(v, snap);
					LuaValue k = LuaValue.NIL;
					while (true) {
						Varargs n = t.next(k);
						if ((k = n.arg1()).isnil())
							break;
						snap.keys.add(export(k, where + "[key]"));
						snap.values.add(export(n.arg(2), where + "." + k.tojstring()));
					}
					LuaValue mt = t.getmetatable();
					if (mt != null && !mt.isnil())
						snap.metatable = export(mt, "metatable of " + where);
					return snap;
				}
				case LuaValue.TFUNCTION: {
					String lib = libraryPath(v);
					if (lib != null)
						return new LibRef(lib);
					if (!(v instanceof LuaClosure c))
						throw new LuaError("cannot send " + where + " to another Lua state: Java function " + v.tojstring());
					Object done = seen.get(v);
					if (done != null)
						return done;
					ClosureSnap snap = new ClosureSnap(c.p, c.upValues.length);
					seen.put(v, snap);
					for (int i = 0; i < c.upValues.length; i++) {
						UpValue uv = c.upValues[i];
						UpvalSnap us = (UpvalSnap) seen.get(uv);
						if (us == null) {
							us = new UpvalSnap();
							seen.put(uv, us);
							LuaValue value = uv.getValue();
							if (value == from.globals)
								us.isGlobals = true;
							else
								us.value = export(value, "upvalue '" + upvalueName(c.p, i) + "' of " + where);
						}
						snap.upvalues[i] = us;
					}
					return snap;
				}
				case LuaValue.TUSERDATA: {
					Object done = seen.get(v);
					if (done != null)
						return done;
					Object exported;
					try {
						exported = policy.export((LuaUserdata) v);
					} catch (LuaError e) {
						throw new LuaError("cannot send " + where + " to another Lua state: " + e.getMessage());
					}
					UserdataSnap snap = new UserdataSnap(exported);
					seen.put(v, snap);
					return snap;
				}
				default:
					throw new LuaError("cannot send " + where + " to another Lua state: " + v.typename());
			}
		}

		private String libraryPath(LuaValue v) {
			if (libraries == null)
				libraries = indexLibraries(from);
			return libraries.get(v);
		}
	}

	private static String upvalueName(Prototype p, int i) {
		if (p.upvalues != null && i < p.upvalues.length && p.upvalues[i] != null && p.upvalues[i].name != null)
			return p.upvalues[i].name.tojstring();
		return "?";
	}

	/**
	 * Paths of the library objects of a state: global Java functions ("print"), global tables of Java functions
	 * ("math") and their functions ("math.floor"), and loaded modules ("package.loaded.async", ...).
	 */
	private static IdentityHashMap<LuaValue, String> indexLibraries(LuaState state) {
		IdentityHashMap<LuaValue, String> index = new IdentityHashMap<>();
		LuaTable g = state.globals;
		LuaValue k = LuaValue.NIL;
		while (true) {
			Varargs n = g.next(k);
			if ((k = n.arg1()).isnil())
				break;
			if (!k.isstring())
				continue;
			LuaValue v = n.arg(2);
			if (v.isfunction() && !v.isclosure())
				index.putIfAbsent(v, k.tojstring());
			else if (v.istable() && v != g && isLibraryTable((LuaTable) v))
				indexTable(index, (LuaTable) v, k.tojstring(), '.');
		}
		LuaValue loaded = g.get("package").get("loaded");
		if (loaded.istable()) {
			k = LuaValue.NIL;
			while (true) {
				Varargs n = loaded.next(k);
				if ((k = n.arg1()).isnil())
					break;
				LuaValue v = n.arg(2);
				if (k.isstring() && v.istable() && v != g && !index.containsKey(v) && isLibraryTable((LuaTable) v))
					indexTable(index, (LuaTable) v, LOADED + k.tojstring(), SEP);
			}
		}
		return index;
	}

	/** A table whose function values are all Java functions (a library, not a Lua-defined module). */
	private static boolean isLibraryTable(LuaTable t) {
		boolean anyFunction = false;
		LuaValue k = LuaValue.NIL;
		while (true) {
			Varargs n = t.next(k);
			if ((k = n.arg1()).isnil())
				break;
			LuaValue v = n.arg(2);
			if (v.isclosure())
				return false;
			if (v.isfunction())
				anyFunction = true;
		}
		return anyFunction;
	}

	private static void indexTable(Map<LuaValue, String> index, LuaTable t, String path, char sep) {
		index.putIfAbsent(t, path);
		LuaValue k = LuaValue.NIL;
		while (true) {
			Varargs n = t.next(k);
			if ((k = n.arg1()).isnil())
				break;
			LuaValue v = n.arg(2);
			if (k.isstring() && v.isfunction())
				index.putIfAbsent(v, path + sep + k.tojstring());
		}
	}

	// ---- import --------------------------------------------------------------------------------------

	private static final class Importer {
		final LuaState to;
		final UserdataPolicy policy;
		final IdentityHashMap<Object, Object> built = new IdentityHashMap<>();

		Importer(LuaState to, UserdataPolicy policy) {
			this.to = to;
			this.policy = policy;
		}

		LuaValue materialize(Object o) {
			if (o instanceof LuaValue v)
				return v;
			Object done = built.get(o);
			if (done != null)
				return (LuaValue) done;
			if (o instanceof TableSnap s) {
				LuaTable t = new LuaTable();
				built.put(o, t);
				for (int i = 0; i < s.keys.size(); i++)
					t.rawset(materialize(s.keys.get(i)), materialize(s.values.get(i)));
				if (s.metatable != null)
					t.setmetatable(materialize(s.metatable));
				return t;
			}
			if (o instanceof ClosureSnap s) {
				LuaClosure c = new LuaClosure(s.p, LuaValue.NIL, to);
				built.put(o, c);
				UpValue[] ups = new UpValue[s.upvalues.length];
				for (int i = 0; i < ups.length; i++) {
					UpvalSnap us = s.upvalues[i];
					UpValue uv = (UpValue) built.get(us);
					if (uv == null) {
						uv = new UpValue(new LuaValue[] { LuaValue.NIL }, 0);
						built.put(us, uv);
						uv.setValue(us.isGlobals ? to.globals : materialize(us.value));
					}
					ups[i] = uv;
				}
				c.upValues = ups;
				return c;
			}
			if (o instanceof LibRef r) {
				LuaValue v = lookup(r.path);
				if (v.isnil())
					throw new LuaError("'" + r.path.replace(SEP, '.') + "' is not available in the target Lua state");
				built.put(o, v);
				return v;
			}
			if (o instanceof UserdataSnap s) {
				LuaValue v = policy.materialize(s.exported, to);
				built.put(o, v);
				return v;
			}
			throw new IllegalStateException("unknown snapshot node " + o);
		}

		private LuaValue lookup(String path) {
			LuaValue v;
			String rest;
			if (path.startsWith(LOADED)) {
				rest = path.substring(LOADED.length());
				int sep = rest.indexOf(SEP);
				String module = sep < 0 ? rest : rest.substring(0, sep);
				v = to.globals.get("package").get("loaded").get(module);
				if (sep < 0)
					return v;
				return v.istable() ? v.get(rest.substring(sep + 1)) : LuaValue.NIL;
			}
			v = to.globals;
			for (String part : path.split("\\.")) {
				if (!v.istable())
					return LuaValue.NIL;
				v = v.get(part);
			}
			return v;
		}
	}
}
