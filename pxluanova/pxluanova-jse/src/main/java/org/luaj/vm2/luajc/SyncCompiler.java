package org.luaj.vm2.luajc;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.UpValue;

public class SyncCompiler {

	private static final String PREFIX_UPVALUE = "u";
	private final JavaLoader sharedLoader = new JavaLoader(LuaFunction.class.getClassLoader());
	private final AtomicLong counter = new AtomicLong();
	private final Map<Prototype, Class<?>> prototypeCache = new WeakHashMap<>();

	public LuaFunction compile(LuaClosure source) {
		Class<?> clazz = prototypeCache.get(source.p);
		if (clazz == null) {
			String classname = "nova_sync_" + counter.incrementAndGet();
			JavaGen gen;
			try {
				gen = new JavaGen(source.p, classname, classname + ".lua", false);
			} catch (Exception e) {
				throw new LuaError("nova.sync compilation failed: " + e.getMessage());
			}
			sharedLoader.include(gen);
			try {
				clazz = sharedLoader.loadClass(gen.classname);
			} catch (Exception e) {
				throw new LuaError("nova.sync load failed: " + e.getMessage());
			}
			prototypeCache.put(source.p, clazz);
		}

		LuaFunction compiled;
		try {
			compiled = (LuaFunction) clazz.getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new LuaError("nova.sync instantiation failed: " + e.getMessage());
		}

		try {
			Class<?> c = compiled.getClass();
			for (int i = 0; i < source.upValues.length; i++) {
				Field field = c.getDeclaredField(PREFIX_UPVALUE + i);
				field.setAccessible(true);
				UpValue uv = source.upValues[i];
				if (field.getType() == LuaValue[].class) {
					field.set(compiled, uv.getValueHolder());
				} else {
					field.set(compiled, uv.getValue());
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new LuaError("nova.sync upvalue setup failed: " + e.getMessage());
		}

		return compiled;
	}
}
