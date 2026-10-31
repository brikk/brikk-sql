package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import kotlin.String
import kotlin.collections.List
import kotlin.test.*

class ClickhouseCompositionValidationTest {
    private fun validate(sql: String, schema: Schema? = null, version: String = "26.10.2.103") =
        validateClickhouseCompositions(parseOne(sql, "clickhouse"), version, schema)

    private fun rules(report: ClickhouseCompositionValidation) = report.errors.map { it.rule }.toSet()
    private fun reasons(report: ClickhouseCompositionValidation) = report.unknowns.map { it.reason }.toSet()

    @Test
    fun variadicLeftDesignatorsRequireExplicitPlaceholders() {
        for (name in listOf("trim", "trimBoth", "toString", "concat")) {
            val report = validate("SELECT arrayMap($name | upper,['x'])")
            assertTrue(ClickhouseCompositionRule.VARIADIC_LEFT_DESIGNATOR in rules(report), name)
        }
        for (body in listOf("trim(_) | upper", "toString(_) | upper", "concat('x',_) | upper"))
            assertTrue(validate("SELECT arrayMap($body,['x'])").errors.isEmpty(), body)
    }

    @Test
    fun variadicRightDesignatorsReceiveOneArgumentWithoutBecomingLeftDesignators() {
        for (body in listOf("lower | trim", "lower | concat", "lower | toString"))
            assertTrue(validate("SELECT arrayMap($body,['x'])").errors.isEmpty(), body)
        assertTrue(validate("SELECT arrayMap(multiply | toString,[2],[3])").errors.isEmpty())
        val nested = validate("SELECT arrayMap(lower | (concat | upper),['x'])")
        assertTrue(ClickhouseCompositionRule.VARIADIC_LEFT_DESIGNATOR in rules(nested))
    }

    @Test
    fun rightOperandArityAndArrayCountAreSeparateConstraints() {
        for (body in listOf("lower | multiply", "lower | concat(_1,_2)", "lower | (multiply | toString)"))
            assertTrue(ClickhouseCompositionRule.RIGHT_OPERAND_ARITY in rules(validate("SELECT arrayMap($body,['x'])")), body)
        for (query in listOf("SELECT arrayMap(_2,[1])", "SELECT arrayMap((x,y) -> x,[1])",
            "SELECT arrayMap(multiply | toString,[1])"))
            assertTrue(ClickhouseCompositionRule.ARRAY_MAP_ARITY in rules(validate(query)), query)
        assertTrue(ClickhouseCompositionRule.ARRAY_INPUT_TYPE in rules(validate("SELECT arrayMap(lower | upper,1)")))
    }

    @Test
    fun anonymousPlaceholdersAreLocalDirectArgumentsNotNumberedAliasesOrMembers() {
        assertTrue(ClickhouseCompositionRule.MIXED_PLACEHOLDERS in rules(validate("SELECT arrayMap(_ + _1,[1])")))
        assertTrue(ClickhouseCompositionRule.ANONYMOUS_DEPTH in rules(validate("SELECT arrayMap(upper(trim(_)) | lower,['x'])")))
        assertTrue(ClickhouseCompositionRule.ANONYMOUS_MEMBER in rules(validate("SELECT arrayMap(_.x | upper,[tuple('x')])")))
        assertTrue(validate("SELECT arrayMap(upper(trim(_1)) | lower,['x'])").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap((_1 + 1) | (_ * 2),[1])").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap(tuple(_,_),[1],['a'])").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap(tuple(_1,_1),[1])").errors.isEmpty())
    }

    @Test
    fun placeholderBoundsAreCheckedWithoutAllocatingFromAnAuthoredOrdinal() {
        for (name in listOf("_129", "_999999999999999999999999999999"))
            assertTrue(ClickhouseCompositionRule.PLACEHOLDER_LIMIT in rules(validate("SELECT arrayMap($name,[1])")), name)
        val maximum = validate("SELECT arrayMap(_128,[1])")
        assertFalse(ClickhouseCompositionRule.PLACEHOLDER_LIMIT in rules(maximum))
        assertTrue(ClickhouseCompositionRule.ARRAY_MAP_ARITY in rules(maximum))
        assertTrue(validate("SELECT arrayMap(_0,[1])").errors.isEmpty())
    }

    @Test
    fun nativeInputFamiliesCanProveIncompatibilityWithoutGuessingIntegerWidths() {
        for (query in listOf("SELECT arrayMap(lower | upper,[1,2])",
            "SELECT arrayMap(lower(_) | upper,CAST([1] AS Array(UInt64)))",
            "SELECT arrayMap(multiply(_1,_2) | lower,[2],[3])"))
            assertTrue(ClickhouseCompositionRule.INPUT_TYPE in rules(validate(query)), query)
        for (query in listOf("SELECT arrayMap(lower | upper,['x'])",
            "SELECT arrayMap(multiply(_1,_2) | toString | upper,[2],[3])",
            "SELECT arrayMap(lower | concat('$',_),['x'])"))
            assertTrue(validate(query).errors.isEmpty(), query)
    }

    @Test
    fun explicitCallCountsRespectInputDependentAndEarlyNullRules() {
        assertTrue(ClickhouseCompositionRule.CALL_ARGUMENT_COUNT in rules(validate("SELECT arrayMap(toString(_1,'UTC') | upper,[1])")))
        assertTrue(ClickhouseCompositionRule.CALL_ARGUMENT_COUNT in rules(validate("SELECT arrayMap(trimBoth(_1,'x','y') | upper,['x'])")))
        assertTrue(validate("SELECT arrayMap(x -> toString(NULL,1,2),['x'])").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap(x -> trimBoth(NULL,'x','y'),['x'])").errors.isEmpty())
        assertTrue(ClickhouseCompositionRule.CALL_ARGUMENT_COUNT in rules(validate("SELECT arrayMap(x -> multiply(NULL,1,2),['x'])")))
        assertTrue(validate("SELECT arrayMap(x -> toString(TRY_CAST('bad' AS UInt8),'UTC'),['x'])").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap(x -> toString(CAST(NULL AS UInt8),'UTC'),['x']) SETTINGS cast_keep_nullable=1").errors.isEmpty())
        val nullable = validate("SELECT arrayMap(lower | upper,CAST([NULL] AS Array(Nullable(UInt8))))")
        assertTrue(nullable.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.NULL_OR_WRAPPER in reasons(nullable))
        val timezone = validate("SELECT arrayMap(toString(_1,'UTC') | upper,CAST([] AS Array(DateTime)))")
        assertTrue(timezone.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.TIMEZONE_VALUE in reasons(timezone))
        assertTrue(validate("SELECT arrayMap(lower | upper,CAST(['x'] AS Array(CHAR)))").errors.isEmpty())
        assertTrue(validate("SELECT arrayMap(toString(_1,'UTC') | upper,CAST([0] AS Array(TIMESTAMP)))").errors.isEmpty())
    }

    @Test
    fun fixedStringsKeepRuntimeRestrictionsSeparateFromTypeErrors() {
        assertTrue(validate("SELECT arrayMap(lower | upper,CAST(['abc'] AS Array(FixedString(3))))").errors.isEmpty())
        val url = validate("SELECT arrayMap(domainWithoutWWW(_) | upper,CAST(['abc'] AS Array(FixedString(3))))")
        assertTrue(url.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.RUNTIME_RESTRICTION in reasons(url))
        assertTrue(ClickhouseCompositionRule.INPUT_TYPE in rules(validate("SELECT arrayMap(trim(_1,CAST('x' AS FixedString(1))) | upper,['x'])")))
    }

    @Test
    fun arrayLengthEqualityAndCompositeOverloadsAreNeverCertifiedByArity() {
        val lengths = validate("SELECT arrayMap(multiply(_1,_2) | toString,[1,2],[3])")
        assertTrue(lengths.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.ARRAY_LENGTHS in reasons(lengths))
        val arrays = validate("SELECT arrayMap(concat(_1,_2) | lower,CAST([[1]] AS Array(Array(UInt8))),CAST([[2]] AS Array(Array(UInt8))))")
        assertTrue(arrays.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.INPUT_TYPE in reasons(arrays))
        val heterogeneous = validate("SELECT arrayMap(xs -> arrayMap(lower | upper,xs),[[1],['x']])")
        assertTrue(heterogeneous.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.INPUT_TYPE in reasons(heterogeneous))
    }

    @Test
    fun scopedAliasesParametersAndPhysicalNamesDoNotBorrowBuiltinFacts() {
        for (query in listOf(
            "WITH (x -> upper(x)) AS trim SELECT arrayMap(trim | upper,['x'])",
            "SELECT arrayMap(trim -> arrayMap(trim | upper,['x']),['x'])",
            "SELECT arrayMap(x -> tuple(x AS trim,arrayMap(trim | upper,['x'])),['x'])",
            "SELECT arrayMap(trim | upper,['x']) FROM (SELECT 1 AS trim) t",
            "WITH 1 AS _1 SELECT arrayMap(_1,[1])",
            "WITH ((f,a) -> ['ok']) AS arrayMap SELECT arrayMap(trim | upper,['x'])",
        )) {
            val report = validate(query)
            assertTrue(report.errors.isEmpty(), query)
            assertTrue(ClickhouseCompositionUnknownReason.NAME_RESOLUTION in reasons(report), query)
        }
        val unknownTable = validate("SELECT arrayMap(lower | upper,t.a) FROM t")
        assertTrue(unknownTable.errors.isEmpty())
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Array(UInt8)")), dialect = Dialects.CLICKHOUSE)
        assertTrue(ClickhouseCompositionRule.INPUT_TYPE in rules(validate("SELECT arrayMap(lower | upper,t.a) FROM t", schema)))
        val shadow = MappingSchema(mapOf("t" to mapOf("trim" to "UInt8")), dialect = Dialects.CLICKHOUSE)
        assertTrue(validate("SELECT arrayMap(trim | upper,['x']) FROM t", shadow).errors.isEmpty())
        val foreignSchema = MappingSchema(mapOf("t" to mapOf("a" to "Array(INT)")))
        assertTrue(validate("SELECT arrayMap(trim | upper,['x']) FROM t", foreignSchema).errors.isEmpty())
    }

    @Test
    fun derivedCastsAreProofsButGenericTryCastAnnotationsAreNot() {
        assertTrue(ClickhouseCompositionRule.INPUT_TYPE in rules(validate(
            "SELECT arrayMap(lower | upper,t.a) FROM (SELECT CAST([1] AS Array(UInt8)) a) t")))
        val query = "SELECT arrayMap(x -> lower(t.n),t.a) FROM (SELECT TRY_CAST('bad' AS UInt8) n,CAST(['x'] AS Array(String)) a) t"
        val ast = annotateTypes(parseOne(query, "clickhouse"), dialect = Dialects.CLICKHOUSE)
        assertTrue(validateClickhouseCompositions(ast, "26.10.2.103").errors.isEmpty())
        val collision = validate("SELECT CAST([1] AS Array(UInt8)) AS a,arrayMap(lower | upper,a) FROM (SELECT CAST(['x'] AS Array(String)) a) t SETTINGS prefer_column_name_to_alias = 1")
        assertTrue(collision.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.INPUT_TYPE in reasons(collision))
    }

    @Test
    fun unresearchedNamesVersionsAndParserGapsRemainUnknownNotInvalidOrAccepted() {
        for (query in listOf("SELECT arrayMap(my_udf | upper,['x'])", "SELECT arrayMap(reverse | upper,['x'])",
            "SELECT arrayMap(TOSTRING | upper,[1])", "SELECT arrayMap(TRIMBOTH(_) | upper,['x'])")) {
            val report = validate(query)
            assertTrue(report.errors.isEmpty(), query)
            assertTrue(ClickhouseCompositionUnknownReason.UNRESEARCHED_CALLABLE in reasons(report), query)
        }
        val parserGap = validate("SELECT arrayMap((x -> toString(x)) | (y -> toTypeName(y)),[1])")
        assertTrue(parserGap.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.PARSER_GAP in reasons(parserGap))
        for (version in listOf("", "26.8.34.13", "26.10.2.104", "27.1.1.1")) {
            val report = validate("SELECT arrayMap(trim | upper,['x'])", version = version)
            assertTrue(report.errors.isEmpty())
            assertEquals(setOf(ClickhouseCompositionUnknownReason.ENGINE_VERSION), reasons(report))
        }
    }

    @Test
    fun unsupportedFunctionContextsAndUnusedWithDefinitionsDoNotGetFalseErrors() {
        for (query in listOf("SELECT lower | upper", "SELECT arrayCount(trim | upper,['x'])",
            "SELECT arrayCount(x -> arrayMap(trim | upper,['x']),['x'])",
            "WITH (x -> upper(x)) AS trim SELECT arrayMap(trim | upper,['x']) UNION ALL SELECT ['ok']",
            "WITH arrayMap(trim | upper,['x']) AS unused SELECT 1")) {
            val report = validate(query)
            assertTrue(report.errors.isEmpty(), query)
            assertTrue(ClickhouseCompositionUnknownReason.UNSUPPORTED_CONTEXT in reasons(report), query)
        }
    }

    @Test
    fun validationIsReadOnlyKeepsIssueIdentityAndPropagatesCancellation() {
        val ast = annotateTypes(parseOne("SELECT arrayMap(trim | upper,['x'])", "clickhouse"), dialect = Dialects.CLICKHOUSE)
        val before = Serde.dump(ast)
        val sql = ast.sql("clickhouse")
        val report = validateClickhouseCompositions(ast, "26.10.2.103")
        assertEquals(before, Serde.dump(ast))
        assertEquals(sql, ast.sql("clickhouse"))
        assertTrue(report.errors.all { finding -> ast.walk().any { it === finding.expression } })
        val messages = validate("SELECT arrayMap(trimBoth(_1,'do-not-log-me','also-secret') | upper,['x'])").errors.map { it.message }
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.none { "do-not-log-me" in it || "also-secret" in it })
        val physical = parseOne("SELECT arrayMap(lower | upper,t.a) FROM t", "clickhouse")
        val physicalBefore = Serde.dump(physical)
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Array(UInt8)")), dialect = Dialects.CLICKHOUSE)
        validateClickhouseCompositions(physical, "26.10.2.103", schema)
        assertEquals(physicalBefore, Serde.dump(physical))
        assertFailsWith<IllegalStateException> {
            validateClickhouseCompositions(ast, "26.10.2.103", checkCanceled = { error("cancelled") })
        }
    }

    @Test
    fun deepAnalysisStopsWithUnknownInsteadOfOverflowOrAnInventedError() {
        fun ref() = ClickhouseFunctionReference(args("this" to Identifier(args("this" to "lower"))))
        var chain: Expression = ref()
        repeat(512) { chain = ClickhouseComposition(args("this" to chain, "expression" to ref())) }
        val input = parseOne("CAST(['x'] AS Array(String))", "clickhouse")
        val ast = Transform(args("this" to input, "expression" to chain))
        val report = validateClickhouseCompositions(ast, "26.10.2.103")
        assertTrue(report.errors.isEmpty())
        assertTrue(ClickhouseCompositionUnknownReason.ANALYSIS_LIMIT in reasons(report))
    }
}
