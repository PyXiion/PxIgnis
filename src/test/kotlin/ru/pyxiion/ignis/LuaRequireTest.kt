package ru.pyxiion.ignis

import ru.pyxiion.ignis.packages.PackageManifest
import ru.pyxiion.ignis.packages.PackagePlan
import ru.pyxiion.ignis.runtime.installStdlib
import ru.pyxiion.ignis.runtime.installStrictGlobals
import ru.pyxiion.ignis.sandbox.Vfs
import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaState
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuaRequireTest {
    private val root: Path = Files.createTempDirectory("ignis-require")

    private fun file(rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private fun pkg(id: String, vararg depends: String, body: String = "return {id='$id'}", extra: Map<String, String> = emptyMap()): PackageManifest {
        val dir = root.resolve("packages/$id")
        file("packages/$id/init.lua", body)
        extra.forEach { (k, v) -> file("packages/$id/$k", v) }
        return PackageManifest.parse(
            """{"id":"$id","version":"1.0.0","depends":{${depends.joinToString(",") { "\"pkg:$it\":\"*\"" }}}}""", dir,
        )
    }

    private fun run(script: String, plan: PackagePlan = PackagePlan.build(emptyList(), emptyMap()), plugins: ru.pyxiion.ignis.sandbox.PluginModules? = null): Varargs {
        val state = LuaState()
        installStdlib(state, Vfs(listOf(root)), plan, plugins)
        installStrictGlobals(state.globals)
        LuaState.setCurrent(state)
        return state.load(script, "test").checkfunction().invoke()
    }

    @Test
    fun `unknown namespace does not fall back to a folder`() {
        file("mymod/utils.lua", "return 1")
        val r = run("return pcall(require, 'mymod:utils')")
        assertFalse(r.arg(1).toboolean())
        assertTrue("unknown namespace 'mymod:'" in r.arg(2).tojstring(), r.arg(2).tojstring())
        assertEquals(1, run("return require('mymod.utils')").arg(1).toint())
    }

    @Test
    fun `pkg entry and submodules`() {
        val a = pkg("arena", extra = mapOf("ui/menu.lua" to "return 'menu'", "ui/init.lua" to "return 'ui'"))
        val plan = PackagePlan.build(listOf(a), emptyMap())
        val r = run("return require('pkg:arena').id, require('pkg:arena.ui.menu'), require('pkg:arena.ui')", plan)
        assertEquals("arena", r.arg(1).tojstring())
        assertEquals("menu", r.arg(2).tojstring())
        assertEquals("ui", r.arg(3).tojstring())
    }

    @Test
    fun `bare require inside a package stays in the package`() {
        file("util.lua", "return 'loose'")
        val a = pkg("arena", body = "local u = require('util'); return {u=u, same = u == require('pkg:arena.util')}", extra = mapOf("util.lua" to "return {}"))
        val b = pkg("other", body = "return pcall(require, 'util')")
        val plan = PackagePlan.build(listOf(a, b), emptyMap())
        val ar = run("return require('pkg:arena').same, type(require('pkg:arena').u), require('util')", plan)
        assertTrue(ar.arg(1).toboolean())
        assertEquals("table", ar.arg(2).tojstring())
        assertEquals("loose", ar.arg(3).tojstring())
        val br = run("return require('pkg:other')", plan)
        assertFalse(br.arg(1).toboolean())
    }

    @Test
    fun `package code sets globals visible to everyone`() {
        val a = pkg("arena", body = "shared_value = 42")
        val plan = PackagePlan.build(listOf(a), emptyMap())
        assertEquals(42, run("require('pkg:arena'); return shared_value", plan).arg(1).toint())
    }

    @Test
    fun `missing and failed packages`() {
        val bad = pkg("bad", body = "error('boom')")
        val user = pkg("user", "bad", body = "return 1")
        val plan = PackagePlan.build(listOf(bad, user), emptyMap())
        val none = run("return pcall(require, 'pkg:nothing')", plan)
        assertTrue("package 'nothing' is not installed" in none.arg(2).tojstring(), none.arg(2).tojstring())
        val r = run("local ok, e = pcall(require, 'pkg:bad'); local ok2, e2 = pcall(require, 'pkg:user'); return ok, e, ok2, e2", plan)
        assertFalse(r.arg(1).toboolean())
        assertTrue("boom" in r.arg(2).tojstring())
        assertFalse(r.arg(3).toboolean())
        assertTrue("dependency 'bad' failed" in r.arg(4).tojstring(), r.arg(4).tojstring())
        assertTrue(plan.failure("user") != null)
    }

    @Test
    fun `require cycle shows the chain`() {
        val a = pkg("a", body = "return require('pkg:b')")
        val b = pkg("b", body = "return require('pkg:a')")
        val r = run("return pcall(require, 'pkg:a')", PackagePlan.build(listOf(a, b), emptyMap()))
        assertFalse(r.arg(1).toboolean())
        assertTrue("circular require: pkg:a -> pkg:b -> pkg:a" in r.arg(2).tojstring(), r.arg(2).tojstring())
    }

    @Test
    fun `ext modules`() {
        val plugins = ru.pyxiion.ignis.sandbox.PluginModules { id, sub, caller ->
            if (id != "voice") throw LuaError("plugin '$id' is not installed")
            LuaValue.valueOf("voice/${sub ?: "main"}/${caller ?: "-"}")
        }
        val a = pkg("arena", body = "return require('ext:voice.x')")
        val plan = PackagePlan.build(listOf(a), emptyMap())
        val r = run("return require('ext:voice'), require('pkg:arena'), pcall(require, 'ext:nope')", plan, plugins)
        assertEquals("voice/main/-", r.arg(1).tojstring())
        assertEquals("voice/x/arena", r.arg(2).tojstring())
        assertTrue("plugin 'nope' is not installed" in r.arg(4).tojstring(), r.arg(4).tojstring())
    }
}
