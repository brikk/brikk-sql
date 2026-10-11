package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.Resolver
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.optimizer.qualifyColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Explicit policy tests: no assertion that a future upstream-shaped overload infers. */
class InferencePolicyContractTest {
    @Test
    fun optionalSchemaLineageRetainsBestEffortUnnestDependenciesNotStrictColumnProof() {
        val sql = "WITH _data AS (SELECT [STRUCT(1 AS a,2 AS b)] AS col) SELECT b FROM _data CROSS JOIN UNNEST(_data.col)"
        val node = dev.brikk.house.sql.optimizer.lineage("b", sql)
        assertEquals("_0.b", node.downstream.single().name)
        assertEquals("_data.col", node.downstream.single().downstream.single().name)
    }
    @Test
    fun upstreamResolverConstructorDoesNotInferButExplicitLegacyConstructionStillCan() {
        val dialect = Dialects.POSTGRES
        val scope = buildScope(dialect.parseOne("SELECT amount FROM mystery AS m"))!!
        val schema = MappingSchema(dialect = dialect)
        assertEquals(null, Resolver(scope, schema).getTable("amount"))
        assertEquals(null, Resolver(scope, schema, inferSchema = false).getTable("amount"))
        assertEquals("m", Resolver(scope, schema, inferSchema = true).getTable("amount")?.name)
    }

    @Test
    fun upstreamShapedOverloadsSelectDisabledInferenceWhileExplicitNullRetainsLegacyDefault() {
        for (dialect in listOf(Dialects.POSTGRES, Dialects.DUCKDB, Dialects.DORIS)) {
            val source = "SELECT amount FROM mystery AS m"
            val upstream = qualify(dialect.parseOne(source), dialect = dialect, validateQualifyColumns = false)
            val disabled = qualify(dialect.parseOne(source), dialect = dialect,
                inferSchema = false, validateQualifyColumns = false)
            assertEquals(disabled, upstream, dialect.name)
            assertEquals("", upstream.findAll<Column>().single().table, dialect.name)
            assertFailsWith<OptimizeError>(dialect.name) { qualify(dialect.parseOne(source), dialect = dialect) }
            val legacy = qualify(dialect.parseOne(source), dialect = dialect,
                inferSchema = null, validateQualifyColumns = false)
            assertEquals("m", legacy.findAll<Column>().single().table, dialect.name)
            val upstreamColumns = qualifyColumns(dialect.parseOne(source), schema = emptyMap<String, Any?>(), dialect = dialect)
            val disabledColumns = qualifyColumns(dialect.parseOne(source), schema = emptyMap<String, Any?>(),
                dialect = dialect, inferSchema = false)
            assertEquals(disabledColumns, upstreamColumns, dialect.name)
            assertEquals("", upstreamColumns.findAll<Column>().single().table, dialect.name)
        }
    }

    @Test
    fun aliasExpansionAfterEvidenceBasedQualificationKeepsKnownAndUnknownSourcesSeparate() {
        for (dialect in listOf(Dialects.POSTGRES, Dialects.DUCKDB, Dialects.DORIS)) {
            val source = "SELECT amount AS chosen, chosen AS copied FROM orders AS o ORDER BY copied"
            val schema = mapOf("orders" to mapOf("amount" to "INT"))
            val known = qualify(dialect.parseOne(source), dialect = dialect, schema = schema)
            assertEquals(listOf("chosen", "copied"), known.expressionsArg.map { (it as dev.brikk.house.sql.ast.Expression).alias })
            assertEquals(setOf("o"), known.findAll<Column>().filter { it.name == "amount" }.map { it.table }.toSet())
            val unknown = qualify(dialect.parseOne(source), dialect = dialect, validateQualifyColumns = false)
            assertEquals(setOf(""), unknown.findAll<Column>().filter { it.name == "amount" }.map { it.table }.toSet())
        }
    }

    @Test
    fun explicitLegacyInferenceAndDisabledInferenceDisagreeOnlyWhereEvidenceIsMissing() {
        for (dialect in listOf(Dialects.POSTGRES, Dialects.DUCKDB, Dialects.DORIS)) {
            val source = "SELECT amount FROM mystery AS m"
            for (infer in listOf(true, false, null)) {
                val qualified = qualify(dialect.parseOne(source), dialect = dialect,
                    inferSchema = infer, validateQualifyColumns = false)
                assertEquals(if (infer == false) "" else "m", qualified.findAll<Column>().single().table,
                    "${dialect.name}/qualify/$infer")
                val columns = qualifyColumns(dialect.parseOne(source), schema = emptyMap<String, Any?>(),
                    dialect = dialect, inferSchema = infer)
                assertEquals(if (infer == false) "" else "m", columns.findAll<Column>().single().table,
                    "${dialect.name}/qualifyColumns/$infer")
            }
            qualify(dialect.parseOne(source), dialect = dialect, inferSchema = true)
            assertFailsWith<OptimizeError>(dialect.name) {
                qualify(dialect.parseOne(source), dialect = dialect, inferSchema = false)
            }
        }
    }

    @Test
    fun nullableLegacyPolicyKeepsItsExistingEmptyVersusPopulatedSchemaDefault() {
        val dialect = Dialects.POSTGRES
        val source = "SELECT amount FROM mystery AS m"
        val unrelated = mapOf("unrelated" to mapOf("secret" to "TEXT"))
        val automatic = qualify(dialect.parseOne(source), dialect = dialect, schema = unrelated,
            inferSchema = null, validateQualifyColumns = false)
        assertEquals("", automatic.findAll<Column>().single().table)
        val legacy = qualify(dialect.parseOne(source), dialect = dialect, schema = unrelated,
            inferSchema = true, validateQualifyColumns = false)
        assertEquals("m", legacy.findAll<Column>().single().table)
        val columnsAutomatic = qualifyColumns(dialect.parseOne(source), schema = unrelated,
            dialect = dialect, inferSchema = null)
        assertEquals("", columnsAutomatic.findAll<Column>().single().table)
        val columnsLegacy = qualifyColumns(dialect.parseOne(source), schema = unrelated,
            dialect = dialect, inferSchema = true)
        assertEquals("m", columnsLegacy.findAll<Column>().single().table)
    }

    @Test
    fun completeSchemasBehaveIdenticallyWithInferenceEnabledOrDisabled() {
        for (dialect in listOf(Dialects.POSTGRES, Dialects.DUCKDB, Dialects.DORIS)) {
            val source = "SELECT m.amount AS chosen FROM orders AS m ORDER BY chosen"
            val schema = mapOf("orders" to mapOf("amount" to "INT"))
            val enabled = qualify(dialect.parseOne(source), dialect = dialect, schema = schema, inferSchema = true)
            val disabled = qualify(dialect.parseOne(source), dialect = dialect, schema = schema, inferSchema = false)
            assertEquals(enabled, disabled, dialect.name)
            for (infer in listOf(true, false)) {
                assertFailsWith<OptimizeError>("${dialect.name}/$infer") {
                    qualify(dialect.parseOne("SELECT absent FROM orders"), dialect = dialect, schema = schema, inferSchema = infer)
                }
            }
        }
    }

    @Test
    fun inferenceNeverInventsAColumnOwnerAmongMultipleUnknownSources() {
        for (dialect in listOf(Dialects.POSTGRES, Dialects.DUCKDB, Dialects.DORIS)) {
            val source = "SELECT amount FROM mystery AS a CROSS JOIN other AS b"
            for (infer in listOf(true, false, null)) {
                val result = qualify(dialect.parseOne(source), dialect = dialect,
                    inferSchema = infer, validateQualifyColumns = false)
                assertEquals("", result.findAll<Column>().single().table, "${dialect.name}/$infer")
                assertFailsWith<OptimizeError>("${dialect.name}/$infer") {
                    qualify(dialect.parseOne(source), dialect = dialect, inferSchema = infer)
                }
            }
        }
    }
}
