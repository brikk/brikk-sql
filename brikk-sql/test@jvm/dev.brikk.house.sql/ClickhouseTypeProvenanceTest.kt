package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.diagnostics.*
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.parser.parseOne
import kotlin.String
import kotlin.test.*

class ClickhouseTypeProvenanceTest {
    private val version = "26.10.2.103"

    @Test fun nativeConditionalFallbackAndUnknownHaveDistinctEvidence() {
        val sql = "SELECT 1+2 AS a, 1%2 AS b, unknown_udf(1) AS c"
        val ast = parseOne(sql, "clickhouse")
        val generated = ast.sql("clickhouse")
        val result = annotateClickhouseSemanticTypesWithProvenance(ast, version)
        assertSame(ast, result.expression)
        assertEquals(generated, ast.sql("clickhouse"))
        assertEquals(ast.walk().map { it.objectId }.toSet(), result.provenance.facts.map { it.nodeId }.toSet())
        val add = result.provenance.factFor(ast.find<Add>()!!)!!
        assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, add.evidence)
        assertEquals("UInt16", add.typeSql)
        val mul = result.provenance.factFor(ast.find<Mod>()!!)!!
        assertEquals(ClickhouseTypeEvidence.GENERIC_FALLBACK, mul.evidence)
        assertEquals("Nullable(Int32)", mul.typeSql)
        val udf = ast.findAll<Anonymous>().single { it.name == "unknown_udf" }
        assertEquals(ClickhouseTypeEvidence.UNKNOWN, result.provenance.factFor(udf)!!.evidence)
        assertEquals(Serde.dump(ast), Serde.dump(Serde.load(Serde.dump(ast)) as Expression))
    }

    @Test fun conditionalProofsCoexistWithErrorsAndScriptStateStaysExplicit() {
        val sql = "SET sql_compatible_division_for_integers=1; SELECT 1/0; SELECT 1e0*(2e0+3.0)"
        val result = annotateClickhouseScriptSemanticTypesWithProvenance(sql, version)
        assertEquals(clickhouseScriptSemanticDiagnostics(sql, version), result.map { it.annotation.diagnostics })
        val division = result[1].annotation.expression!!.find<Div>()!!
        val fact = result[1].provenance!!.factFor(division)!!
        assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, fact.evidence)
        assertEquals("UInt8", fact.typeSql)
        assertEquals(ClickhouseSemanticRule.DIVISION_BY_ZERO, result[1].annotation.diagnostics.errors.single().rule)
        val product = result[2].annotation.expression!!.find<Mul>()!!
        assertEquals(ClickhouseTypeEvidence.NATIVE_CONDITIONAL, result[2].provenance!!.factFor(product)!!.evidence)
        val local = annotateClickhouseSemanticTypesWithProvenance(parseOne("SELECT 1/0", "clickhouse"), version)
        assertEquals(ClickhouseTypeEvidence.UNKNOWN, local.provenance.factFor(local.expression.find<Div>()!!)!!.evidence)
    }

    @Test fun snapshotsNeverTransferToCopiesSerdeOrOtherCallsAndDoNotMutateOldApis() {
        val sql = "SELECT 1+2"
        val ast = parseOne(sql, "clickhouse")
        val result = annotateClickhouseSemanticTypesWithProvenance(ast, version)
        for (copy in listOf(ast.copy(), Serde.load(Serde.dump(ast)) as Expression,
            annotateClickhouseSemanticTypesWithProvenance(parseOne(sql, "clickhouse"), version).expression)) {
            assertTrue(copy.walk().all { result.provenance.factFor(it) == null })
        }
        val captured = result.provenance.factFor(ast.find<Add>()!!)!!
        ast.find<Add>()!!.typeSlot = DataType(args("this" to DType.VARCHAR))
        assertEquals("UInt16", captured.typeSql) // Snapshot is historical; mutation requires new analysis.
        assertEquals(Serde.dump(annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), version)),
            Serde.dump(annotateClickhouseSemanticTypesWithProvenance(parseOne(sql, "clickhouse"), version).expression))
        val generic = annotateTypes(parseOne(sql, "clickhouse"), dialect = dev.brikk.house.sql.dialects.Dialects.CLICKHOUSE)
        assertEquals("Nullable(Int32)", generic.find<Add>()!!.type!!.sql("clickhouse"))
    }

    @Test fun exactPinSchemaCancellationAndFramingRulesRemainUnchanged() {
        assertFailsWith<IllegalArgumentException> { annotateClickhouseSemanticTypesWithProvenance(parseOne("SELECT 1", "clickhouse"), "26.8.34.13") }
        assertFailsWith<IllegalArgumentException> { annotateClickhouseScriptSemanticTypesWithProvenance("SELECT 1", "26.8.34.13") }
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseScriptSemanticTypesWithProvenance("SELECT 1", version) { throw stop } }.exceptionOrNull())
        val failed = annotateClickhouseScriptSemanticTypesWithProvenance("SELECT (", version)
        assertTrue(failed.isNotEmpty())
        assertTrue(failed.all { it.annotation.expression == null && it.provenance == null })
        val callable = annotateClickhouseScriptSemanticTypesWithProvenance("SELECT arrayMap(lower | upper,['Hi'])", version).single()
        assertTrue(callable.annotation.expression!!.findAll<ClickhouseComposition>().all {
            callable.provenance!!.factFor(it)!!.evidence == ClickhouseTypeEvidence.UNKNOWN
        })
    }
}
