package ru.pyxiion.ignis

import ru.pyxiion.ignis.packages.PackageManifest
import ru.pyxiion.ignis.packages.PackagePlan
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackagePlanTest {
    private fun m(id: String, version: String = "1.0.0", depends: Map<String, String> = emptyMap(), optional: Map<String, String> = emptyMap()): PackageManifest {
        fun obj(m: Map<String, String>) = m.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return PackageManifest.parse(
            """{"id":"$id","version":"$version","depends":{${obj(depends)}},"optional":{${obj(optional)}}}""",
            Path.of("/pkgs/$id"),
        )
    }

    @Test
    fun `manifest defaults and fields`() {
        val p = PackageManifest.parse("""{"id":"arena","version":"1.2.3","depends":{"pkg:shops":">=1.0","ext:voice":"*"},"optional":{"pkg:stats":"*"}}""", Path.of("/x"))
        assertEquals("init.lua", p.entry)
        assertEquals(listOf("pkg:shops", "ext:voice"), p.depends.map { it.ref })
        assertEquals(listOf("pkg:stats"), p.dependencies.filter { it.optional }.map { it.ref })
    }

    @Test
    fun `invalid manifests`() {
        val dir = Path.of("/x")
        fun bad(json: String) = assertFailsWith<IllegalArgumentException> { PackageManifest.parse(json, dir) }
        bad("not json")
        bad("""{"version":"1"}""")
        bad("""{"id":"A b","version":"1"}""")
        bad("""{"id":"a","version":"1","entry":"../x.lua"}""")
        bad("""{"id":"a","version":"1","depends":{"shops":"*"}}""")
        bad("""{"id":"a","version":"1","depends":{"pkg:b":"1.x.*.y >="}}""")
        bad("""{"id":"a","version":"1","depends":[]}""")
    }

    @Test
    fun `dependencies load first and ids break ties`() {
        val plan = PackagePlan.build(
            listOf(m("zeta"), m("alpha", depends = mapOf("pkg:zeta" to "*")), m("beta"), m("mid", optional = mapOf("pkg:alpha" to "*"))),
            emptyMap(),
        )
        assertEquals(listOf("beta", "zeta", "alpha", "mid"), plan.order)
        assertTrue(plan.failures.isEmpty())
    }

    @Test
    fun `missing dependency fails the package and its dependents`() {
        val plan = PackagePlan.build(
            listOf(m("a", depends = mapOf("pkg:ghost" to "*")), m("b", depends = mapOf("pkg:a" to "*")), m("c", optional = mapOf("pkg:a" to "*")), m("d")),
            emptyMap(),
        )
        assertTrue("not installed" in plan.failure("a")!!, plan.failure("a"))
        assertTrue("dependency 'a' failed" in plan.failure("b")!!, plan.failure("b"))
        assertNull(plan.failure("c"))
        assertEquals(listOf("c", "d"), plan.order)
    }

    @Test
    fun `version ranges for packages and plugins`() {
        val plan = PackagePlan.build(
            listOf(
                m("lib", version = "1.4.0"),
                m("ok", depends = mapOf("pkg:lib" to ">=1.2", "ext:voice" to "~0.3")),
                m("old", depends = mapOf("pkg:lib" to ">=2.0")),
                m("novoice", depends = mapOf("ext:radio" to "*")),
                m("badplugin", depends = mapOf("ext:voice" to ">=1.0")),
                m("opt", optional = mapOf("pkg:lib" to ">=9")),
                m("optmissing", optional = mapOf("pkg:nothing" to "*", "ext:nothing" to "*")),
            ),
            mapOf("voice" to "0.3.2"),
        )
        assertNull(plan.failure("ok"))
        assertTrue("1.4.0 is installed" in plan.failure("old")!!, plan.failure("old"))
        assertTrue("plugin 'radio'" in plan.failure("novoice")!!)
        assertTrue("0.3.2 is installed" in plan.failure("badplugin")!!)
        assertTrue("1.4.0 is installed" in plan.failure("opt")!!)
        assertNull(plan.failure("optmissing"))
    }

    @Test
    fun `cycles name the chain and spare bystanders`() {
        val plan = PackagePlan.build(
            listOf(
                m("a", depends = mapOf("pkg:b" to "*")), m("b", depends = mapOf("pkg:c" to "*")), m("c", depends = mapOf("pkg:a" to "*")),
                m("user", depends = mapOf("pkg:a" to "*")), m("free"),
            ),
            emptyMap(),
        )
        assertEquals(listOf("free"), plan.order)
        assertTrue("a -> b -> c -> a" in plan.failure("a")!!, plan.failure("a"))
        assertTrue("dependency 'a' failed" in plan.failure("user")!!)
    }

    @Test
    fun `duplicate ids fail with both paths and cascade`() {
        val plan = PackagePlan.build(
            listOf(m("dep", depends = mapOf("pkg:dup" to "*"))), emptyMap(),
            mapOf("dup" to listOf("/a/dup", "/b/dup")),
        )
        assertTrue("/a/dup" in plan.failure("dup")!! && "/b/dup" in plan.failure("dup")!!)
        assertTrue(plan.failure("dep") != null)
        assertTrue(plan.order.isEmpty())
    }

    @Test
    fun `runtime failure cascades through depends only`() {
        val plan = PackagePlan.build(
            listOf(m("a"), m("b", depends = mapOf("pkg:a" to "*")), m("c", depends = mapOf("pkg:b" to "*")), m("d", optional = mapOf("pkg:a" to "*"))),
            emptyMap(),
        )
        val skipped = plan.fail("a", "boom")
        assertEquals(listOf("a", "b", "c"), skipped.map { it.first })
        assertNull(plan.failure("d"))
    }
}
