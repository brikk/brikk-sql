package dev.brikk.house.sql.intellij.testing

import junit.framework.TestCase

/**
 * The test kit's public data classes plugins compile against keep their earlier JVM constructors and
 * `copy` when a defaulted field is added (AGENTS.md): plugin tests built against the earlier shape still link.
 * Neither earlier shape had defaults, so there is no synthetic defaults constructor to keep.
 */
class TestKitBinaryCompatibilityTest : TestCase() {
    fun testRealSqlModelKeepsItsShapeFromBeforeNamespaces() {
        val map = Map::class.java
        val list = List::class.java
        val exact = RealSqlModel::class.java.getConstructor(map, list, list)
        val model = exact.newInstance(mapOf("a.b.t" to listOf("x")), listOf("a.b.u"), emptyList<String>())
        assertEquals(RealSqlModel(mapOf("a.b.t" to listOf("x")), listOf("a.b.u"), emptyList()), model)
        val copy = RealSqlModel::class.java.getMethod("copy", map, list, list)
        val widened = RealSqlModel.STANDARD.copy(namespaces = listOf("ext_catalog"))
        assertEquals(widened.copy(bare = emptyList()), copy.invoke(widened, widened.tables, emptyList<String>(), widened.absent))
    }

    fun testRealSqlCaseKeepsItsShapeFromBeforeMissed() {
        val string = String::class.java
        val set = Set::class.java
        val shape = arrayOf(string, string, set, Map::class.java, List::class.java, set)
        val exact = RealSqlCase::class.java.getConstructor(*shape)
        assertEquals(RealSqlCase("c", "SELECT 1"), exact.newInstance("c", "SELECT 1", emptySet<Any>(), emptyMap<String, String>(), emptyList<Any>(), emptySet<String>()))
        val case = RealSqlCase("c", "SELECT 1", missed = setOf("x"))
        val copy = RealSqlCase::class.java.getMethod("copy", *shape)
        assertEquals(case.copy(name = "d"), copy.invoke(case, "d", "SELECT 1", emptySet<Any>(), emptyMap<String, String>(), emptyList<Any>(), emptySet<String>()))
    }

    fun testRealSqlCompletionKeepsItsShapeFromBeforeOnce() {
        val string = String::class.java
        val set = Set::class.java
        val map = Map::class.java
        val exact = RealSqlCompletion::class.java.getConstructor(string, set, set, map)
        assertEquals(RealSqlCompletion("SELECT <caret>", setOf("a"), setOf("b")), exact.newInstance("SELECT <caret>", setOf("a"), setOf("b"), emptyMap<String, String>()))
        val completion = RealSqlCompletion("SELECT <caret>", setOf("a"), once = setOf("a"))
        val copy = RealSqlCompletion::class.java.getMethod("copy", string, set, set, map)
        assertEquals(completion.copy(offered = setOf("c")), copy.invoke(completion, "SELECT <caret>", setOf("c"), emptySet<String>(), emptyMap<String, String>()))
    }

    fun testRealSqlCompletionKeepsItsShapeFromBeforeVariantsAndNotOffered() {
        val string = String::class.java
        val set = Set::class.java
        val exact = RealSqlCompletion::class.java.getConstructor(string, set)
        assertEquals(RealSqlCompletion("SELECT <caret>", setOf("a")), exact.newInstance("SELECT <caret>", setOf("a")))
        val completion = RealSqlCompletion("SELECT <caret>", setOf("a"), notOffered = setOf("b"))
        val copy = RealSqlCompletion::class.java.getMethod("copy", string, set)
        assertEquals(completion.copy(offered = setOf("c")), copy.invoke(completion, "SELECT <caret>", setOf("c")))
    }
}
