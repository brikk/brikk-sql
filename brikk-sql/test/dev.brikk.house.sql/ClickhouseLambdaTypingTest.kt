package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.TypeAnnotator
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.parseOne
import kotlin.String
import kotlin.collections.List
import kotlin.test.*

class ClickhouseLambdaTypingTest {
    private fun typed(sql: String, schema: kotlin.Any? = null): Expression =
        annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)

    private fun type(node: Expression): String =
        if (node.isType(DType.UNKNOWN)) "UNKNOWN" else node.type?.sql("clickhouse") ?: "MISSING"

    private fun parameters(ast: Expression): List<Identifier> = ast.findAll<Lambda>(bfs = false)
        .flatMap { it.expressionsArg.filterIsInstance<Identifier>().asSequence() }.toList()

    private fun placeholders(ast: Expression): List<ClickhouseLambdaArgument> =
        ast.findAll<ClickhouseLambdaArgument>(bfs = false).toList()

    private fun values(ast: Expression, name: String): List<Identifier> = ast.findAll<Identifier>(bfs = false).filter {
        it.name == name && it.parent !is Column && it.parent !is DataType && it.parent !is ColumnDef &&
            !(it.parent is Dot && it.argKey != "this") &&
            !(it.parent is Lambda && it.argKey == "expressions") &&
            !(it.parent is ClickhouseImplicitLambda && it.argKey == "expressions") &&
            it.parent !is ClickhouseLambdaArgument
    }.toList()

    @Test
    fun explicitAndImplicitArgumentsCopyKnownArrayElementTypes() {
        val explicit = typed("SELECT arrayMap(x -> x, CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8"), parameters(explicit).map(::type))
        assertEquals(listOf("UInt8"), values(explicit, "x").map(::type))
        val implicit = typed("SELECT arrayMap(_1, CAST([1] AS Array(UInt16)))")
        assertEquals(listOf("UInt16"), placeholders(implicit).map(::type))
        assertEquals("UInt16", type(implicit.find<ClickhouseImplicitLambda>()!!.expressionsArg.single() as Expression))
        assertEquals("Array(UInt8)", type(explicit.find<Transform>()!!))
        assertTrue(implicit.find<ClickhouseImplicitLambda>()!!.isType(DType.UNKNOWN))
        assertEquals("Array(UInt16)", type(implicit.find<Transform>()!!))
    }

    @Test
    fun multipleArraysRespectNumberedOrderReuseAndAnonymousOccurrences() {
        val explicit = typed("SELECT arrayMap((x,y) -> tuple(x,y), CAST([1] AS Array(UInt8)), CAST(['a'] AS Array(String)))")
        assertEquals(listOf("UInt8", "String"), parameters(explicit).map(::type))
        val numbered = typed("SELECT arrayMap(tuple(_2,_1,_1), CAST([1] AS Array(UInt8)), CAST(['a'] AS Array(String)))")
        assertEquals(listOf("String", "UInt8", "UInt8"), placeholders(numbered).map(::type))
        val anonymous = typed("SELECT arrayMap(tuple(_,_), CAST([1] AS Array(Int8)), CAST(['a'] AS Array(String)))")
        assertEquals(listOf("Int8", "String"), placeholders(anonymous).map(::type))
        val gap = typed("SELECT arrayMap(_2, CAST([1] AS Array(UInt8)), CAST(['a'] AS Array(String)))")
        assertEquals(listOf("String"), placeholders(gap).map(::type))
    }

    @Test
    fun nestedExplicitLambdasCaptureAndShadowWithoutSharingBindings() {
        val captured = typed("SELECT arrayMap(x -> arrayMap(y -> tuple(x,y), CAST(['a'] AS Array(String))), CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8", "String"), parameters(captured).map(::type))
        assertEquals(listOf("UInt8"), values(captured, "x").map(::type))
        assertEquals(listOf("String"), values(captured, "y").map(::type))
        val shadowed = typed("SELECT arrayMap(x -> arrayMap(x -> x, CAST(['a'] AS Array(String))), CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8", "String"), parameters(shadowed).map(::type))
        assertEquals(listOf("String"), values(shadowed, "x").map(::type))
        val siblings = typed("SELECT arrayMap(x -> x, CAST([1] AS Array(UInt8))), arrayMap(x -> x, CAST(['a'] AS Array(String)))")
        assertEquals(listOf("UInt8", "String"), values(siblings, "x").map(::type))
    }

    @Test
    fun nestedArrayInputsAreTypedBeforeTheirInnerLambda() {
        val explicit = typed("SELECT arrayMap(xs -> arrayMap(x -> x, xs), CAST([[1]] AS Array(Array(UInt16))))")
        assertEquals(listOf("Array(UInt16)", "UInt16"), parameters(explicit).map(::type))
        val implicit = typed("SELECT arrayMap(arrayMap(_ + 1, _1), CAST([[1]] AS Array(Array(UInt8))))")
        assertEquals(listOf("Array(UInt8)", "UInt8"), placeholders(implicit).map(::type))
    }

    @Test
    fun explicitAndImplicitScopesCanCaptureEachOtherWithoutOrdinalNameCollisions() {
        val explicitOuter = typed("SELECT arrayMap(x -> arrayMap(tuple(x,_1), CAST([2] AS Array(UInt64))), CAST([1] AS Array(Int16)))")
        assertEquals(listOf("Int16"), parameters(explicitOuter).map(::type))
        assertEquals(listOf("Int16"), values(explicitOuter, "x").map(::type))
        assertEquals(listOf("UInt64"), placeholders(explicitOuter).map(::type))
        val implicitOuter = typed("SELECT arrayMap(arrayMap(y -> tuple(y,_1), CAST(['a'] AS Array(String))), CAST([1] AS Array(Int8)))")
        assertEquals(listOf("String"), parameters(implicitOuter).map(::type))
        assertEquals(listOf("Int8"), placeholders(implicitOuter).map(::type))
        val namedUnderscore = typed("SELECT arrayMap(_1 -> arrayMap(_1 -> _1, CAST(['a'] AS Array(String))), CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8", "String"), parameters(namedUnderscore).map(::type))
        assertEquals(listOf("String"), values(namedUnderscore, "_1").map(::type))
        val anonymousOuter = typed("SELECT arrayMap(arrayMap(_1 -> _1,_),CAST([[1]] AS Array(Array(UInt8))))")
        assertEquals(listOf("Array(UInt8)"), placeholders(anonymousOuter).map(::type))
        assertEquals(listOf("UInt8"), values(anonymousOuter, "_1").map(::type))
    }

    @Test
    fun physicalColumnsAndUnderscoreColumnsKeepTheirOwnSchemaTypes() {
        val schema = mapOf("t" to mapOf("a" to "Array(UInt8)", "n" to "UInt32", "_1" to "String", "_" to "Int16"))
        val ast = typed("SELECT arrayMap(_1 -> tuple(_1,t.n,t._1),t.a),t._,t._1 FROM t", schema)
        assertEquals(listOf("UInt8"), parameters(ast).map(::type))
        assertEquals(listOf("UInt8"), values(ast, "_1").map(::type))
        assertEquals(listOf("UInt32"), ast.findAll<Column>().filter { it.name == "n" }.map(::type).toList())
        assertTrue(ast.findAll<Column>().filter { it.name == "_1" }.all { type(it) == "String" })
        assertEquals("Int16", type(ast.findAll<Column>().single { it.name == "_" }))
        assertTrue(placeholders(ast).isEmpty())
    }

    @Test
    fun unknownAndMalformedBindingInputsStayUnknownWithoutLeakingOuterTypes() {
        for (sql in listOf(
            "SELECT arrayMap(x -> x,t.a) FROM t",
            "SELECT arrayMap(x -> x,1)",
            "SELECT arrayMap((x,y) -> x,CAST([1] AS Array(UInt8)))",
        )) assertTrue(parameters(typed(sql)).all { type(it) == "UNKNOWN" }, sql)
        for (sql in listOf(
            "SELECT arrayMap(_ + _1,CAST([1] AS Array(UInt8)))",
            "SELECT arrayMap(_2,CAST([1] AS Array(UInt8)))",
            "SELECT arrayMap(_129,CAST([1] AS Array(UInt8)))",
        )) assertTrue(placeholders(typed(sql)).all { type(it) == "UNKNOWN" }, sql)
        val boundary = typed("SELECT arrayMap(x -> arrayFilter(x -> x,CAST(['a'] AS Array(String))),CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8", "String"), parameters(boundary).map(::type)) // CHSEM-008 binds arrayFilter independently.
        assertEquals(listOf("String"), values(boundary, "x").map(::type))
        val query = typed("SELECT arrayMap(x -> (SELECT x),CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8"), parameters(query).map(::type))
        assertTrue(values(query, "x").all { type(it) == "UNKNOWN" })
    }

    @Test
    fun compositionReceivesProvedResultsAtLaterStages() {
        val implicit = typed("SELECT arrayMap(toString(_) | toTypeName(_),CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8", "String"), placeholders(implicit).map(::type))
        // CHSEM-011 tracks the existing SQL parser gap for parenthesized explicit
        // lambda operands. Correct caller-supplied Lambda ASTs still bind by context.
        val explicit = parseOne("SELECT arrayMap(x -> toString(x),CAST([1] AS Array(UInt8)))", "clickhouse")
        val call = explicit.find<Transform>()!!
        val right = parseOne("SELECT arrayMap(y -> toTypeName(y),CAST(['a'] AS Array(String)))", "clickhouse").find<Lambda>()!!.copy()
        call.set("expression", ClickhouseComposition(args(
            "this" to Paren(args("this" to call.args["expression"])),
            "expression" to Paren(args("this" to right)),
        )))
        annotateTypes(explicit, dialect = Dialects.CLICKHOUSE)
        assertEquals(listOf("UInt8", "String"), parameters(explicit).map(::type))
        assertTrue(explicit.findAll<ClickhouseComposition>().all { it.isType(DType.UNKNOWN) })
        assertTrue(explicit.findAll<Lambda>().all { it.isType(DType.UNKNOWN) })
    }

    @Test
    fun fieldAndFunctionNameIdentifiersAreNotParameterReferences() {
        val ast = typed("SELECT arrayMap((x,n) -> tuple(x.n,n),CAST([tuple('a')] AS Array(Tuple(n String))),CAST([1] AS Array(UInt8)))")
        val dot = ast.find<Dot>()!!
        assertEquals("Tuple(n String)", type(dot.thisArg as Expression))
        assertEquals("UNKNOWN", type(dot.args["expression"] as Expression))
        assertEquals("UInt8", type(values(ast, "n").last()))
        val designator = typed("SELECT arrayMap(lower -> arrayMap(lower,CAST(['a'] AS Array(String))),CAST([1] AS Array(UInt8)))")
        assertEquals("UNKNOWN", type(designator.find<ClickhouseFunctionReference>()!!.thisArg as Expression))
    }

    @Test
    fun knownElementShapeAndNullabilityAreCopiedRatherThanInferredOrMutated() {
        for (element in listOf("Nullable(UInt8)", "FixedString(3)", "Array(UInt16)")) {
            val ast = typed("SELECT arrayMap(x -> x,CAST([] AS Array($element)))")
            val formal = parameters(ast).single()
            val reference = values(ast, "x").single()
            val source = (ast.find<Cast>()!!.args["to"] as DataType).expressionsArg.single() as DataType
            assertEquals(element, type(formal))
            assertEquals(source, formal.type)
            assertNotSame(source, formal.type)
            assertNotSame(formal.type, reference.type)
            (formal.type as DataType).set("nullable", true)
            assertEquals(element, type(reference))
            assertEquals(element, source.sql("clickhouse"))
        }
    }

    @Test
    fun overwriteAndCacheInvalidationKeepTheExistingAnnotatorContract() {
        val schema = MappingSchema(dialect = Dialects.CLICKHOUSE)
        val ast = parseOne("SELECT arrayMap(x -> x,CAST([1] AS Array(UInt8)))", "clickhouse")
        val annotator = TypeAnnotator(schema)
        annotator.annotate(ast)
        assertEquals(listOf("UInt8"), parameters(ast).map(::type))
        // Invalidate the whole dependent tree after changing the input's known type.
        ast.find<Cast>()!!.set("to", Dialects.CLICKHOUSE.parseOne("CAST([] AS Array(UInt64))").find<Cast>()!!.args["to"])
        annotator.uncache(ast)
        annotator.annotate(ast)
        assertEquals(listOf("UInt64"), parameters(ast).map(::type))
        annotator.clear()
        annotator.annotate(ast)
        assertEquals(listOf("UInt64"), parameters(ast).map(::type))
        val partial = parseOne("SELECT arrayMap(x -> x,CAST([1] AS Array(UInt8)))", "clickhouse")
        parameters(partial).single().typeSlot = DataType(args("this" to DType.DOUBLE, "nullable" to false))
        annotateTypes(partial, dialect = Dialects.CLICKHOUSE, overwriteTypes = false)
        assertEquals("Float64", type(parameters(partial).single()))
        assertEquals("UInt8", type(values(partial, "x").single()))
    }

    @Test
    fun otherDialectsKeepTheirExistingLambdaTyping() {
        val ast = parseOne("SELECT arrayMap(x -> x,CAST([1] AS Array(UInt8)))", "clickhouse")
        annotateTypes(ast, dialect = Dialects.DUCKDB)
        assertTrue(parameters(ast).all { it.isType(DType.UNKNOWN) })
        assertTrue(values(ast, "x").all { it.isType(DType.UNKNOWN) })
    }

    @Test
    fun partialArrayKnowledgeDoesNotTurnUnknownInputsIntoKnownParameters() {
        val ast = typed("SELECT arrayMap((x,y) -> tuple(x,y),CAST([1] AS Array(UInt8)),t.missing) FROM t")
        assertEquals(listOf("UInt8", "UNKNOWN"), parameters(ast).map(::type))
        val nestedResult = typed("SELECT arrayMap(x -> x,arrayMap(y -> y,CAST([1] AS Array(UInt8))))")
        assertEquals(listOf("UInt8", "UInt8"), parameters(nestedResult).map(::type))
        val genericLiterals = typed("SELECT arrayMap((x,y) -> x*y,[1,2],[3,4])")
        assertEquals(listOf("UNKNOWN", "UNKNOWN"), parameters(genericLiterals).map(::type))
        assertTrue(genericLiterals.find<Mul>()!!.isType(DType.UNKNOWN))
    }

    @Test
    fun knownArgumentsDoNotCertifyGenericArithmeticWidths() {
        val ast = typed("SELECT arrayMap(x -> tuple(x+1,x*x,-x),CAST([1] AS Array(UInt8)))")
        assertEquals(listOf("UInt8"), parameters(ast).map(::type))
        assertTrue(ast.find<Add>()!!.isType(DType.UNKNOWN))
        assertEquals("UInt16", type(ast.find<Mul>()!!)) // Native multiplication rule, not generic widening.
        assertTrue(ast.find<Neg>()!!.isType(DType.UNKNOWN))
        val supplied = parseOne("SELECT arrayMap(x -> x*x,CAST([1] AS Array(UInt8)))", "clickhouse")
        annotateTypes(supplied, dialect = Dialects.CLICKHOUSE,
            expressionMetadata = Dialects.CLICKHOUSE.expressionMetadata +
                (Mul::class to TypingSpec.Returns(DType.DOUBLE)))
        assertTrue(supplied.find<Mul>()!!.isType(DType.DOUBLE))
    }

    @Test
    fun annotationKeepsSqlSourcePositionsAndSerializedBindings() {
        val sql = "SELECT arrayMap((x,y) -> tuple(x,y),CAST([1] AS Array(UInt8)),CAST(['a'] AS Array(String)))"
        val ast = parseOne(sql, "clickhouse")
        val rendered = ast.sql("clickhouse")
        val positions = ast.walk().map { it.metaOrNull?.filterKeys { key -> key in setOf("start", "end", "line", "col") } }.toList()
        annotateTypes(ast, dialect = Dialects.CLICKHOUSE)
        assertEquals(rendered, ast.sql("clickhouse"))
        assertEquals(positions, ast.walk().map { it.metaOrNull?.filterKeys { key -> key in setOf("start", "end", "line", "col") } }.toList())
        val restored = Serde.load(Serde.dump(ast)) as Expression
        assertEquals(listOf("UInt8", "String"), parameters(restored).map(::type))
        assertEquals(listOf("UInt8", "String"), parameters(annotateTypes(restored, dialect = Dialects.CLICKHOUSE)).map(::type))
    }

    @Test
    fun deeplyNestedScopesUseAnIterativeWalkAndLinkedBindings() {
        val capture = Identifier(args("this" to "x0", "quoted" to false))
        var body: Expression = capture
        val input = parseOne("CAST([1] AS Array(UInt8))", "clickhouse")
        repeat(1_000) { offset ->
            val parameter = Identifier(args("this" to "x${999 - offset}", "quoted" to false))
            body = Transform(args("this" to input.copy(),
                "expression" to Lambda(args("this" to body, "expressions" to listOf(parameter)))))
        }
        TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE)).annotate(body, annotateScope = false)
        assertEquals("UInt8", type(capture))
        assertEquals(1_000, parameters(body).size)
    }
}
