package ru.pyxiion.luanova.vm2;

import java.util.Locale;

import junit.framework.TestCase;

import ru.pyxiion.luanova.vm2.lib.jse.JsePlatform;

/** Numbers become text the same way on every JVM: a Russian default locale must not give "71,250649" (breaks selectors). */
public class LocaleFormatTest extends TestCase {
	private Locale saved;
	private LuaState state;

	protected void setUp() {
		saved = Locale.getDefault();
		Locale.setDefault(new Locale("ru"));
		state = JsePlatform.standardState();
	}

	protected void tearDown() {
		Locale.setDefault(saved);
	}

	private String eval(String expr) {
		return state.load("return " + expr, "test").invoke().arg1().tojstring();
	}

	public void testFormatFloatsUseDecimalPoint() {
		assertEquals("71.250649", eval("string.format('%f', 71.250649)"));
		assertEquals("5.5", eval("string.format('%.1f', 5.5)"));
		assertEquals("1.500000e+00", eval("string.format('%e', 1.5)"));
		assertEquals("0.500000", eval("string.format('%g', 0.5)"));
		assertEquals("2.50", eval("('%.2f'):format(2.5)"));
	}

	public void testSelectorFormatting() {
		assertEquals("@e[x=71.250649,distance=..4.9]",
			eval("string.format('@e[x=%f,distance=..%.1f]', 71.250649, 4.9)"));
	}

	public void testQuotedAndToStringUseDecimalPoint() {
		assertEquals(-1, eval("string.format('%q', 0.1)").indexOf(','));
		assertEquals("5.5", eval("tostring(5.5)"));
		assertEquals("1.5", eval("1.5 .. ''"));
	}
}
