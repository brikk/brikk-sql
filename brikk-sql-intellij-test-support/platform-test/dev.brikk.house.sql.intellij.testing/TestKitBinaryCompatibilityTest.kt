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
