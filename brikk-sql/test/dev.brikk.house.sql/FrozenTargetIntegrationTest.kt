package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.generator.explodeProjectionToUnnest
import dev.brikk.house.sql.optimizer.findAllInScope
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.qualifyColumns
import dev.brikk.house.sql.optimizer.validateQualifyColumns
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.*

class FrozenTargetIntegrationTest {
    private class UnknownProperty(initArgs: Args = emptyMap()) : Property(initArgs)

    @Test fun constructedStringsUseTheDialectLiteralEscaper() {
        val generator = object : Generator() { override val singleStringInterval get() = true }
        val value = "a'b\\c"
        assertEquals(generator.generate(Literal.string(value)), generator.propertyName(Property(args("this" to Literal.string(value))), stringKey = true))
        assertEquals("INTERVAL ${generator.generate(Literal.string("$value DAY"))}",
            generator.generate(Interval(args("this" to Literal.string(value), "unit" to Var(args("this" to "DAY"))))))
        val badWeek = Interval(args("this" to Literal.string("1'); SELECT 2; --"), "unit" to Var(args("this" to "WEEK"))))
        val rendered = Dialects.PRESTO.generator().generate(badWeek)
        assertTrue(rendered.startsWith("INTERVAL '1'')"), rendered)
        assertIs<Interval>(parseOne(rendered, "presto"))
        val unsupported = Generator()
        assertEquals("", unsupported.propertySql(UnknownProperty()))
        assertEquals(1, unsupported.unsupportedMessages.size)
    }

    @Test fun nestedExplodesStayInTheirOwnQueryAndDoNotCauseOuterZip() {
        val tree = parseOne("SELECT EXPLODE(x) AS a, (SELECT MAX(b) FROM (SELECT EXPLODE(ARRAY(1, 2)) AS b)) AS m FROM tbl", "spark") as Select
        val nestedBefore = tree.findAll<Subquery>().first().copy()
        explodeProjectionToUnnest(tree)
        assertEquals(nestedBefore, tree.findAll<Subquery>().first())
        assertEquals(1, findAllInScope(tree, Unnest::class).count { (it as Unnest).expressionsArg.firstOrNull() !is GenerateSeries })
        assertFalse(findAllInScope(tree, Explode::class).any())
        assertEquals(1, tree.findAll<Explode>().count())
        val declaredType = DataType.build(DType.INT)
        val declaration = Cast(args("this" to Identifier(args("this" to "a")), "to" to declaredType))
        val body = checkNotNull(Parser().replaceLambda(parseOne("a + a"), listOf(declaration)))
        val casts = body.findAll<Cast>().toList()
        assertEquals(2, casts.size)
        assertNotSame(casts[0].args["to"], casts[1].args["to"])
        for (cast in casts) assertSame(cast, (cast.args["to"] as DataType).parent)
        assertSame(declaration, declaredType.parent)
        val ordered = dev.brikk.house.sql.optimizer.qualify(parseOne(
            "SELECT SUM(a) AS x FROM t GROUP BY b ORDER BY SUM(a), SUM(a)"),
            schema = mapOf("t" to mapOf("a" to "INT", "b" to "INT")))
        val orders = (ordered.args["order"] as Order).expressionsArg.filterIsInstance<Ordered>()
        assertEquals(2, orders.size)
        assertNotSame(orders[0].thisArg, orders[1].thisArg)
        for (order in orders) assertSame(order, (order.thisArg as Expression).parent)
    }

    @Test fun copyAndHugeNumericProbesTerminateWithoutClaimingThePythonFailures() {
        for (dialect in listOf("", "postgres")) for (sql in listOf("COPY t FROM :'f'", "COPY t FROM -", "COPY t FROM STDIN CSV -")) {
            val result = runCatching { parseOne(sql, dialect) }
            result.onFailure { assertIs<ParseError>(it, "$dialect: $sql") }
            result.onSuccess { assertEquals(sql, Dialects.forName(dialect).generator().generate(it)) }
        }
        for (text in listOf("1e9999", "-1e9999", "1e1000000", "9223372036854775808")) assertFalse(Literal.number(text).isInt, text)
        assertTrue(Literal.number("42").isInt)
        val quarter = Interval(args("this" to Literal.string("2"), "unit" to Var(args("this" to "QUARTER"))))
        val before = Serde.dump(quarter)
        assertEquals("INTERVAL '6 MONTH'", Dialects.POSTGRES.generator().generate(quarter, copy = false))
        assertEquals(before, Serde.dump(quarter))
        val huge = Interval(args("this" to Literal.string("1e9999"), "unit" to Var(args("this" to "QUARTER"))))
        val generator = Dialects.POSTGRES.generator()
        val sql = generator.generate(huge)
        assertContains(sql, "1e9999")
        assertTrue(generator.unsupportedMessages.isNotEmpty())
    }

    @Test fun refTypeRetainsNamedTargetsWithoutTakingOverFunctionsOrIdentifiers() {
        for (sql in listOf("REF(customer)", "REF(catalog.schema.customer)", "ARRAY<REF(public.customer)>", "STRUCT<manager REF(public.employee)>", "MAP<INT, REF(public.customer)>")) {
            val type = dataTypeFromStr(sql)
            assertTrue(type.findAll<DataType>().any { it.thisArg == DType.REF }, sql)
            assertEquals(type, dataTypeFromStr(Dialects.BASE.generator().generate(type)))
            assertEquals(type, Serde.loadExpression(Serde.dump(type)))
            assertFailsWith<UnsupportedError> { Generator().apply { forExecution = true }.generate(type) }
        }
        for (sql in listOf("SELECT ref FROM ref", "SELECT REF(1)", "SELECT REF(a, b)", "SELECT REF()")) assertFalse(parseOne(sql).findAll<DataType>().any { it.thisArg == DType.REF }, sql)
        for (sql in listOf("REF()", "REF(1)", "REF(a,b)", "REF(a+b)", "REF(a.)")) assertFailsWith<ParseError>(sql) { dataTypeFromStr(sql) }
    }

    @Test fun rawTextHashFlagsAndLegacySchemasSurviveTheRefresh() {
        for (name in listOf("ByteString", "RawString", "UnicodeString", "National", "Placeholder", "JSONPathKey", "AnonymousAggFunc")) {
            val entry = ExpressionRegistry.entries.getValue(name)
            val lower = entry.factory().apply { set("this", "a") }
            val upper = entry.factory().apply { set("this", "A") }
            assertNotEquals(lower, upper, name)
            assertEquals(lower, Serde.loadExpression(Serde.dump(lower)), name)
        }
        assertEquals(listOf("this", "expression"), ArraySort().argTypes.keys.toList())
        assertEquals(listOf("this", "asc", "nulls_first"), SortArray().argTypes.keys.toList())
        assertContains(Lambda().argTypes, "colon")
        assertEquals("brikk.doris", ExpressionRegistry.entries.getValue("AggregateKeyProperty").module)
        val empty = parseOne("ARRAY()")
        annotateTypes(empty)
        val emptyType = assertIs<DataType>(empty.type)
        assertEquals(DType.ARRAY, emptyType.thisArg)
        assertEquals(DType.NULL, (emptyType.expressionsArg.single() as DataType).thisArg)
        val unknown = parseOne("ARRAY(missing)")
        annotateTypes(unknown)
        assertEquals(DType.UNKNOWN, (unknown.type as DataType).thisArg)
    }

    @Test fun refreshedMetadataDoesNotQualifyUnimplementedOptionsOrReserveOrdinaryPasteNames() {
        assertEquals("SELECT paste(paste) FROM paste AS paste", Dialects.CLICKHOUSE.generate(parseOne("SELECT paste(paste) FROM paste AS paste", "clickhouse")))
        assertFailsWith<ParseError> { parseOne("SELECT * FROM t PASTE JOIN u", "clickhouse") }
        val options = listOf<Expression>(
            ArraySort(args("this" to parseOne("[2,1]", "duckdb"), "asc" to true)),
            StrToDate(args("this" to Literal.string("2026"), "format" to Literal.string("YYYY"), "nlsparam" to Literal.string("x"))),
            TemporaryProperty(args("scoped" to true)),
            PrimaryKeyColumnConstraint(args("include" to IndexParameters(args("tablespace" to Identifier(args("this" to "x")))))),
        )
        for (node in options) for (target in listOf("mysql", "presto", "duckdb", "postgres", "bigquery")) {
            assertFailsWith<UnsupportedError>("$target: ${node.key}") { Dialects.forName(target).generator().apply { forExecution = true }.generate(node) }
        }
        val foreignKey = ForeignKey(args("this" to Identifier(args("this" to "fk")),
            "expressions" to listOf(Identifier(args("this" to "a")))))
        assertEquals("FOREIGN KEY fk (a)", Dialects.MYSQL.generator().generate(foreignKey))
        assertFailsWith<UnsupportedError> { Dialects.POSTGRES.generator().apply { forExecution = true }.generate(foreignKey) }
    }

    @Test fun strictUnknownSourcesDoNotInventAliasOrOuterColumnOwnershipWhileLegacyPolicyStaysAvailable() {
        val schema = mapOf("known" to mapOf("a" to "INT"))
        val cases = listOf(
            "SELECT r.range + 1 AS x FROM range(3) AS r WHERE x > 1",
            "SELECT r.range + 1 AS x FROM range(3) AS r GROUP BY x",
            "SELECT SUM(r.range) AS x FROM range(3) AS r HAVING x > 1",
            "SELECT r.range AS x, ROW_NUMBER() OVER () AS n FROM range(3) AS r QUALIFY x > 1",
            "SELECT r.x + 1 AS y FROM (SELECT * FROM READ_PARQUET('f')) AS r WHERE y > 1",
            "SELECT p.a FROM known AS p WHERE EXISTS (SELECT 1 FROM mystery AS q WHERE a > 0)",
        )
        for (sql in cases) {
            assertFailsWith<OptimizeError>(sql) { validateQualifyColumns(qualifyColumns(parseOne(sql), schema, inferSchema = false)) }
            for (legacy in listOf(true, null)) validateQualifyColumns(qualifyColumns(parseOne(sql), schema, inferSchema = legacy))
        }
        for (sql in listOf("SELECT a + 1 AS x FROM known AS r WHERE x > 1",
            "SELECT p.a FROM known AS p WHERE EXISTS (SELECT 1 FROM mystery AS q WHERE p.a > 0)")) {
            validateQualifyColumns(qualifyColumns(parseOne(sql), schema, inferSchema = false))
        }
    }
}
