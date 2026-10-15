package dev.brikk.house.sql

import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.metadata.PropertyDef
import dev.brikk.house.sql.metadata.TRINO_METADATA
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.shape.SqlScriptParameters
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Public data classes other artifacts compile against keep their earlier JVM constructors and `copy`
 * when a defaulted field is added (AGENTS.md): code built against the earlier shape still links.
 */
class BinaryCompatibilityTest {
    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    /** Calls [type]'s synthetic defaults constructor of [parameters] with every argument defaulted. */
    private fun <T> allDefaults(type: Class<T>, vararg parameters: Class<*>): T {
        val constructor = type.getDeclaredConstructor(*parameters, Int::class.javaPrimitiveType, marker)
        return constructor.newInstance(*arrayOfNulls<Any>(parameters.size), (1 shl parameters.size) - 1, null)
    }

    @Test
    fun relationFactsKeepsItsShapeFromBeforeTheSearchPath() {
        val map = Map::class.java
        assertEquals(RelationFacts(), allDefaults(RelationFacts::class.java, map, map, map, CatalogKnowledge::class.java))
        val exact = RelationFacts::class.java.getConstructor(map, map, map, CatalogKnowledge::class.java)
        assertEquals(RelationFacts(catalog = CatalogKnowledge.CLOSED), exact.newInstance(emptyMap<String, Any>(), emptyMap<String, Any>(), emptyMap<String, Any>(), CatalogKnowledge.CLOSED))
        val facts = RelationFacts(searchPath = listOf("one.sales"))
        val copy = RelationFacts::class.java.getMethod("copy", map, map, map, CatalogKnowledge::class.java)
        assertEquals(facts.copy(catalog = CatalogKnowledge.CLOSED), copy.invoke(facts, emptyMap<String, Any>(), emptyMap<String, Any>(), emptyMap<String, Any>(), CatalogKnowledge.CLOSED))
    }

    @Test
    fun sqlScriptParametersKeepsItsShapeFromBeforeAmbiguousRanges() {
        val string = String::class.java
        val shape = arrayOf(string, string, List::class.java, Integer::class.java, string)
        val constructor = SqlScriptParameters::class.java.getConstructor(*shape)
        assertEquals(SqlScriptParameters("x", "clickhouse", emptyList()), constructor.newInstance("x", "clickhouse", emptyList<Any>(), null, null))
        SqlScriptParameters::class.java.getMethod("copy", *shape)
    }

    @Test
    fun dialectMetadataKeepsItsShapeFromBeforeDocs() {
        val metadata = DialectMetadata::class.java.getConstructor(String::class.java, List::class.java, String::class.java)
            .newInstance("x", TRINO_METADATA.snapshots, TRINO_METADATA.default.engineVersion)
        assertEquals(null, metadata.docs)
        assertEquals(TRINO_METADATA.default.engineVersion, metadata.default.engineVersion)
    }

    @Test
    fun propertyDefKeepsItsShapeFromBeforeSummaryAndSince() {
        val list = List::class.java
        val exact = PropertyDef::class.java.getConstructor(String::class.java, list)
        assertEquals(PropertyDef("k", listOf("v")), exact.newInstance("k", listOf("v")))
        val copy = PropertyDef::class.java.getMethod("copy", String::class.java, list)
        val def = PropertyDef("k", emptyList(), "s", "2.0")
        assertEquals(def.copy(name = "j"), copy.invoke(def, "j", emptyList<String>()))
    }

    @Test
    fun engineDocsKeepsItsShapeFromBeforeProperties() {
        val string = String::class.java
        val shape = arrayOf(string, string, string, List::class.java, Map::class.java, Map::class.java, string)
        val docs = EngineDocs::class.java.getConstructor(*shape).newInstance("x", "1", "https://x/", emptyList<Any>(), emptyMap<String, String>(), emptyMap<String, String>(), null)
        assertEquals(emptyList(), docs.properties)
        EngineDocs::class.java.getMethod("copy", *shape)
    }
}
