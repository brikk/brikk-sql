package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import kotlin.String
import kotlin.test.*

class ClickhouseResultInferenceTest {
    private fun type(node: Expression): String = if (node.isType(DType.UNKNOWN)) "UNKNOWN"
        else node.type?.sql("clickhouse") ?: "MISSING"
    private fun typed(sql: String, schema: kotlin.Any? = null) =
        annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
    private fun map(ast: Expression): Expression = ast.walk().first {
        it is Transform || (it is Anonymous && it.name == "arrayMap")
    }

    @Test
    fun supportedStringPipelinesInferArrayResultsAndLaterStageArguments() {
        val ast = typed("SELECT arrayMap(lower | trim(_) | upper,CAST([' x '] AS Array(String)))")
        assertEquals("Array(String)", type(map(ast)))
        assertEquals(listOf("String"), ast.findAll<ClickhouseLambdaArgument>().map(::type).toList())
        assertTrue(ast.findAll<ClickhouseComposition>().all { it.isType(DType.UNKNOWN) })
        val report = inferClickhouseCompositionResults(ast, "26.10.2.103")
        assertTrue(report.validation.errors.isEmpty())
        assertEquals("String", report.callableResults.getValue(ast.find<ClickhouseComposition>()!!.objectId).sql("clickhouse"))
    }

    @Test
    fun exactMultiplicationUsesTheNativePrimitivePromotionRule() {
        for ((left,right,result) in listOf(Triple("UInt8","UInt8","UInt16"), Triple("UInt8","Int32","Int64"),
            Triple("UInt64","UInt64","UInt64"), Triple("Int8","UInt8","Int16"),
            Triple("Float32","Float32","Float64"), Triple("Float64","UInt8","Float64"))) {
            val ast = typed("SELECT arrayMap(multiply,CAST([2] AS Array($left)),CAST([3] AS Array($right)))")
            assertEquals("Array($result)", type(map(ast)), "$left/$right")
        }
        val operator = typed("SELECT arrayMap(x -> x*x,CAST([2] AS Array(UInt8)))")
        assertEquals("UInt16", type(operator.find<Mul>()!!))
        assertEquals("Array(UInt16)", type(map(operator)))
    }

    @Test
    fun unknownNumericWidthsCanStillHaveAProvedStringResult() {
        val ast = typed("SELECT arrayMap(multiply(_1,_2) | toString | CONCAT('$',_),[2],[3])")
        assertEquals("Array(String)", type(map(ast)))
        assertEquals("String", type(ast.findAll<ClickhouseLambdaArgument>(bfs = false).last()))
        assertEquals("UNKNOWN", type(ast.findAll<Anonymous>().first { it.name == "multiply" }))
        assertTrue(typed("SELECT arrayMap((x,y) -> x*y,[2],[3])").find<Mul>()!!.isType(DType.UNKNOWN))
    }

    @Test
    fun identityCopiesKnownNullabilityPrecisionAndContainerShapes() {
        for (element in listOf("UInt8", "Nullable(UInt8)", "FixedString(3)", "Decimal(12, 3)", "Array(UInt16)")) {
            val ast = typed("SELECT arrayMap(_1,CAST([] AS Array($element)))")
            val source = ast.find<Cast>()!!.args["to"] as DataType
            assertEquals(source, map(ast).type, element)
            assertNotSame(source, map(ast).type)
            assertNotSame(source.expressionsArg.single(), (map(ast).type as DataType).expressionsArg.single())
        }
        assertEquals("Array(Nullable(UInt8))", type(map(typed("SELECT arrayMap(x -> x,CAST([1,NULL] AS Array(Nullable(UInt8))))"))))
    }

    @Test
    fun nestedMapsAndCapturedValuesPropagateWithoutLeakingBetweenScopes() {
        val direct = typed("SELECT arrayMap(x -> lower(x),arrayMap(toString(_),CAST([1] AS Array(UInt8))))")
        assertEquals("Array(String)", type(map(direct)))
        assertEquals("String", type(direct.find<Lambda>()!!.expressionsArg.single() as Expression))
        val nested = typed("SELECT arrayMap(xs -> arrayMap(x -> x,xs),CAST([[1]] AS Array(Array(UInt16))))")
        assertEquals("Array(Array(UInt16))", type(map(nested)))
        val captured = typed("SELECT arrayMap(x -> arrayMap(y -> CONCAT(x,y),CAST(['b'] AS Array(String))),CAST(['a'] AS Array(String)))")
        assertEquals("Array(Array(String))", type(map(captured)))
        val projected = typed("SELECT q.a FROM (SELECT arrayMap(toString(_),CAST([1] AS Array(UInt8))) a) q")
        assertEquals("Array(String)", type(projected.selects.single()))
        val reused = typed("SELECT arrayMap(lower | upper,q.a) FROM (SELECT arrayMap(toString(_),CAST([1] AS Array(UInt8))) a) q")
        assertEquals("Array(String)", type(map(reused)))
    }

    @Test
    fun runtimeRestrictionsAndLengthsDoNotBecomeSuccessfulExecutionClaims() {
        val ast = typed("SELECT arrayMap(domainWithoutWWW(_) | upper,CAST([] AS Array(FixedString(3))))")
        assertEquals("Array(FixedString(3))", type(map(ast)))
        val report = inferClickhouseCompositionResults(ast, "26.10.2.103")
        assertTrue(report.validation.unknowns.any { it.reason == ClickhouseCompositionUnknownReason.RUNTIME_RESTRICTION })
        val lengths = typed("SELECT arrayMap(multiply,CAST([1,2] AS Array(UInt8)),CAST([3] AS Array(UInt8)))")
        assertEquals("Array(UInt16)", type(map(lengths)))
        assertTrue(inferClickhouseCompositionResults(lengths,"26.10.2.103").validation.unknowns.any {
            it.reason == ClickhouseCompositionUnknownReason.ARRAY_LENGTHS
        })
    }

    @Test
    fun errorsMissingFactsWrappersAndParserGapsDoNotProduceMapResults() {
        for (sql in listOf("SELECT arrayMap(trim | upper,['x'])", "SELECT arrayMap(lower | multiply,['x'])",
            "SELECT arrayMap(lower | upper,[1])", "SELECT arrayMap(my_udf | upper,['x'])",
            "SELECT arrayMap(_2,[1])", "SELECT arrayMap(upper(trim(_)),['x'])",
            "SELECT arrayMap(x -> 'constant',1)",
            "SELECT arrayMap(toString(_),CAST([NULL] AS Array(Nullable(UInt8))))",
            "SELECT arrayMap(multiply,CAST([1] AS Array(Decimal(12,3))),CAST([2] AS Array(Decimal(12,3))))",
            "SELECT arrayMap((x -> toString(x)) | (y -> upper(y)),[1])")) {
            assertEquals("UNKNOWN", type(map(typed(sql))), sql)
        }
        val old = inferClickhouseCompositionResults(parseOne("SELECT arrayMap(lower | upper,['x'])","clickhouse"), "26.8.34.13")
        assertTrue(old.valueTypes.isEmpty())
        assertTrue(old.callableResults.isEmpty())
    }

    @Test
    fun typingPolicyOverridesAndOverwriteFalseRemainCallerOwned() {
        val ast = parseOne("SELECT arrayMap(x -> lower(x),CAST(['x'] AS Array(String)))","clickhouse")
        annotateTypes(ast, dialect=Dialects.CLICKHOUSE, expressionMetadata=Dialects.CLICKHOUSE.expressionMetadata +
            (Lower::class to TypingSpec.Returns(DType.DOUBLE)))
        assertTrue(ast.find<Lower>()!!.isType(DType.DOUBLE))
        assertEquals("UNKNOWN", type(map(ast)))
        val replacement = parseOne("SELECT arrayMap(lower | upper,['x'])","clickhouse")
        annotateTypes(replacement,dialect=Dialects.CLICKHOUSE,expressionMetadata=emptyMap())
        assertEquals("UNKNOWN",type(map(replacement)))
        val preserved = parseOne("SELECT arrayMap(_1,CAST([1] AS Array(UInt8)))","clickhouse")
        map(preserved).typeSlot = DataType(args("this" to DType.DOUBLE,"nullable" to false))
        annotateTypes(preserved,dialect=Dialects.CLICKHOUSE,overwriteTypes=false)
        assertEquals("Float64",type(map(preserved)))
    }

    @Test
    fun scopeProofsKeepShadowedNamesAndUnknownCatalogsOutOfInference() {
        for (sql in listOf("WITH (x -> x) AS trim SELECT arrayMap(trim | upper,['x'])",
            "SELECT arrayMap(lower | upper,t.a) FROM t",
            "SELECT arrayMap(lower | upper,['x']) FROM (SELECT 1 AS lower) t"))
            assertEquals("UNKNOWN",type(map(typed(sql))),sql)
        val schema=mapOf("t" to mapOf("a" to "Array(String)"))
        assertEquals("Array(String)",type(map(typed("SELECT arrayMap(lower | upper,t.a) FROM t",schema))))
        val uncertainElement = DataType(args("this" to DType.VARCHAR)) // Nullability was not supplied.
        val uncertainArray = DataType(args("this" to DType.ARRAY,"nullable" to false,"nested" to true,
            "expressions" to listOf(uncertainElement)))
        val partial = MappingSchema(mapOf("t" to mapOf("a" to uncertainArray)),dialect=Dialects.CLICKHOUSE)
        assertEquals("UNKNOWN",type(map(typed("SELECT arrayMap(_1,t.a) FROM t",partial))))
    }

    @Test
    fun sourceGenerationSerializationAndCacheInvalidationKeepTheirContracts() {
        val ast=parseOne("SELECT arrayMap(lower | upper,CAST(['x'] AS Array(String)))","clickhouse")
        val sql=ast.sql("clickhouse")
        val annotator=TypeAnnotator(MappingSchema(dialect=Dialects.CLICKHOUSE))
        annotator.annotate(ast)
        assertEquals(sql,ast.sql("clickhouse"))
        assertEquals("Array(String)",type(map(Serde.load(Serde.dump(ast)) as Expression)))
        ast.find<Cast>()!!.set("to",parseOne("CAST([1] AS Array(UInt8))","clickhouse").find<Cast>()!!.args["to"])
        annotator.uncache(ast)
        annotator.annotate(ast)
        assertEquals("UNKNOWN",type(map(ast)))
        annotator.clear()
        annotator.annotate(ast)
        assertEquals("UNKNOWN",type(map(ast)))
    }
}
