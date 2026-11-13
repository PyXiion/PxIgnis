package ru.pyxiion.luanova.vm2.compiler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import ru.pyxiion.luanova.vm2.LuaValue;
import ru.pyxiion.luanova.vm2.parser.LuaParser;

public class LuaParserTests extends CompilerUnitTests {

    protected void setUp() throws Exception {
        super.setUp();
        LuaValue.valueOf(true);
    }
	
	protected void doTest(String file) {
		try {
			InputStream is = inputStreamOfFile(file);
			Reader r = new InputStreamReader(is, "ISO-8859-1");
			LuaParser parser = new LuaParser(r);
			parser.Chunk();
		} catch (Exception e) {
			fail(e.getMessage());
			e.printStackTrace();
		}
	}
}
