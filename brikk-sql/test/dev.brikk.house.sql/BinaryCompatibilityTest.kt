package dev.brikk.house.sql

import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.EngineDocs
import dev.brikk.house.sql.metadata.PropertyDef
import dev.brikk.house.sql.metadata.TRINO_METADATA
import dev.brikk.house.sql.metadata.TypeDef
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

    @Test
    fun clickhouseCasePoliciesKeepPublishedConstructorsAndDefaults() {
        val list = List::class.java
        val set = Set::class.java
        val bool = Boolean::class.javaPrimitiveType!!
        FunctionCatalog::class.java.getConstructor(list, set, bool).newInstance(emptyList<Any>(), emptySet<String>(), true)
        val defaults = FunctionCatalog::class.java.getConstructor(list, set, bool, Int::class.javaPrimitiveType, marker)
            .newInstance(emptyList<Any>(), null, false, 6, null)
        assertEquals(false, defaults.caseSensitive)
        val string = String::class.java
        assertEquals(TypeDef("x", "X"), TypeDef::class.java.getConstructor(string, string, string).newInstance("x", "X", null))
        TypeDef::class.java.getConstructor(string, string, string, Int::class.javaPrimitiveType, marker)
            .newInstance("x", "X", null, 4, null)
        val value = TypeDef("x", "X", caseInsensitive = true)
        assertEquals(value.copy(name = "y"), TypeDef::class.java.getMethod("copy", string, string, string).invoke(value, "y", "X", null))
        TypeDef::class.java.getDeclaredMethod("copy\$default", TypeDef::class.java, string, string, string, Int::class.javaPrimitiveType, Any::class.java)
    }

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
    fun relationFactsKeepsItsShapeFromBeforeCallSites() {
        val map = Map::class.java
        val shape = arrayOf(map, map, map, CatalogKnowledge::class.java, List::class.java)
        assertEquals(RelationFacts(), allDefaults(RelationFacts::class.java, *shape))
        val fact = dev.brikk.house.sql.scope.RelationFact()
        val facts = RelationFacts(callSites = mapOf(12 to fact))
        val copy = RelationFacts::class.java.getMethod("copy", *shape)
        assertEquals(facts, copy.invoke(facts, emptyMap<String, Any>(), emptyMap<String, Any>(), emptyMap<String, Any>(), CatalogKnowledge.OPEN, emptyList<String>()))
        RelationFacts::class.java.getDeclaredMethod("copy\$default", RelationFacts::class.java, *shape, Int::class.javaPrimitiveType, Any::class.java)
        RelationFacts::class.java.getConstructor(*shape)
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

    @Test
    fun metadataSnapshotKeepsItsShapeFromBeforeTypes() {
        val list = List::class.java
        val snapshot = MetadataSnapshot::class.java
            .getConstructor(String::class.java, FunctionCatalog::class.java, Class.forName("dev.brikk.house.sql.metadata.KeywordCatalog"), list, list, list, list, list, list)
            .newInstance("1", DUCKDB_METADATA.default.functions, null, null, null, null, null, null, null)
        assertEquals(null, snapshot.types)
    }

    @Test
    fun duckdbDumpKeepsItsShapeFromBeforeTypes() {
        val list = List::class.java
        val dump = DuckdbDump::class.java.getConstructor(String::class.java, list, list, list, list, list)
            .newInstance("1", emptyList<Any>(), null, null, null, null)
        assertEquals(null, dump.types)
    }

    @Test
    fun functionPresentationKeepsItsShapeFromBeforeNamedParameters() {
        val string = String::class.java
        val shape = arrayOf(string, Boolean::class.java, FunctionKind::class.java, string, string, string, Int::class.java, string, Set::class.java, string)
        val syntaxes = setOf(InvocationSyntax.CALL)
        val old = FunctionPresentation::class.java.getConstructor(*shape).newInstance("f", false, FunctionKind.SCALAR, null, null, null, 0, null, syntaxes, null)
        assertEquals(FunctionPresentation("f", false, FunctionKind.SCALAR, null, null, null, 0, null, syntaxes), old)
        val named = old.copy(namedParameters = listOf("p"))
        assertEquals(named.copy(name = "g"), FunctionPresentation::class.java.getMethod("copy", *shape).invoke(named, "g", false, FunctionKind.SCALAR, null, null, null, 0, null, syntaxes, null))
    }
}
