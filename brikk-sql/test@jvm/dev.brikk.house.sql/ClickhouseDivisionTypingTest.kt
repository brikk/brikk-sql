package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.*
import dev.brikk.house.sql.optimizer.Schema
import dev.brikk.house.sql.parser.parseOne
import java.io.File
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.test.*

class ClickhouseDivisionTypingTest {
    private fun typed(sql: String, native: Boolean = true, schema: Schema? = null): Expression =
        if (native) annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103", schema)
        else annotateTypes(parseOne(sql, "clickhouse"), schema = schema, dialect = Dialects.CLICKHOUSE)
    private fun type(ast: Expression): String {
        val node = (ast as Select).selects.first() as Expression
        return if (node.isType(DType.UNKNOWN)) "UNKNOWN" else node.type?.sql("clickhouse") ?: "MISSING"
    }

    @Test
    fun allIntegerFloatAndNullablePairResultsMatchIndependentNativeTypes() {
        val folder = File(projectRoot(), "vendor/data/clickhouse-semantic/division-types")
        val source = Json.parseToJsonElement(File(folder, "input.json").readText()).jsonObject
        val native = Json.parseToJsonElement(File(folder, "26.10.2.103.json").readText()).jsonObject
            .getValue("batches").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("types").jsonObject }
        val batches = source.getValue("batches").jsonArray.map { row ->
            val batch = row.jsonObject
            val id = batch.getValue("id").jsonPrimitive.content
            val ast = typed(batch.getValue("sql").jsonPrimitive.content) as Select
            val actual = buildJsonObject {
                for (projection in ast.selects.filterIsInstance<Alias>()) {
                    val operation = projection.walk().first { clickhouseDivisionCall(it) != null }
                    assertNotNull(operation.type, "$id/${projection.alias}")
                    put(projection.alias, operation.type!!.sql("clickhouse"))
                }
            }
            assertEquals(196, actual.size, id)
            assertEquals(native.getValue(id), actual, id)
            buildJsonObject { put("id", id); put("types", actual) }
        }
        assertEquals(7, batches.size)
        System.getProperty("brikk.clickhouseDivisionMatrixOut")?.let { path -> File(path).apply {
            parentFile?.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(batches)) + "\n")
        } }
    }

    @Test
    fun schemaFactsNestedModesAndExplicitFunctionDispatchKeepTheirContracts() {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "Nullable(Int128)", "b" to "UInt256")), dialect = Dialects.CLICKHOUSE)
        val sql = "SELECT t.a/t.b AS v FROM t SETTINGS sql_compatible_division_for_integers=1"
        assertEquals("Nullable(Int128)", type(typed(sql, schema = schema)))
        val inherited = typed("SELECT q.v FROM (SELECT CAST(7 AS UInt16)/CAST(2 AS Int8) v) q SETTINGS sql_compatible_division_for_integers=1")
        assertEquals("Int16", type(inherited), inherited.findAll<Div>().map { "${it.type?.sql("clickhouse")}/${clickhouseDivisionCall(it)?.integerMode}" }.toList().toString())
        assertEquals("Float64", type(typed("SELECT q.v FROM (SELECT CAST(7 AS Int64)/CAST(2 AS Int64) v SETTINGS sql_compatible_division_for_integers=0) q SETTINGS sql_compatible_division_for_integers=1")))
        assertEquals("UInt16", type(typed("SELECT divideExact(CAST(7 AS UInt16),CAST(2 AS UInt256)) SETTINGS sql_compatible_division_for_integers=0")))
        assertEquals("Int8", type(typed("SELECT divide(CAST(7 AS UInt8),CAST(2 AS Int256)) SETTINGS sql_compatible_division_for_integers=1")))
    }

    @Test
    fun nativeLiteralWidthsDoNotReplaceTheStandardAnnotatorPolicy() {
        val sql = "SELECT 7/2 SETTINGS sql_compatible_division_for_integers=1"
        assertEquals("UInt8", type(typed(sql)))
        assertEquals("UNKNOWN", type(typed(sql, native = false)))
        assertEquals("Float64", type(typed("SELECT 7/2.0 SETTINGS sql_compatible_division_for_integers=1")))
        assertEquals("UNKNOWN", type(typed("SELECT CAST(7 AS Int64)/CAST(2 AS Int64)")))
        assertTrue((typed("SELECT CAST(7 AS Int64)/CAST(2 AS Int64)", native = false) as Select).selects.filterIsInstance<Expression>().first().isType(DType.DOUBLE))
        assertEquals("Int64", type(typed("SELECT CAST(7 AS Int64)/CAST(2 AS UInt8) SETTINGS sql_compatible_division_for_integers=1", native = false)))
    }

    @Test
    fun unknownOverloadsNullConstantsAndGenericArithmeticAreNotTypeProofs() {
        for (expression in listOf("CAST(NULL AS Nullable(Int8))/CAST(2 AS Int8)",
            "TRY_CAST('bad' AS Int8)/CAST(2 AS Int8)",
            "DIVIDEEXACT(CAST(7 AS Int8),CAST(2 AS Int8))")) {
            assertEquals("UNKNOWN", type(typed("SELECT $expression SETTINGS sql_compatible_division_for_integers=1")), expression)
        }
        val shadowed = "WITH ((a,b) -> 'x') AS divide SELECT divide(CAST(7 AS Int8),CAST(2 AS Int8)) SETTINGS sql_compatible_division_for_integers=1"
        assertEquals("UNKNOWN", type(typed(shadowed)))
        val nested = "SELECT (CAST(1 AS UInt8)+CAST(1 AS UInt8))/CAST(2 AS UInt8) SETTINGS sql_compatible_division_for_integers=1"
        assertEquals("UInt16", type(typed(nested))) // CHSEM-020 proves the intermediate addition width.
        assertEquals("UNKNOWN", type(typed(nested, native = false)))
    }

    @Test
    fun callerPolicyOverwriteCancellationAndCacheInvalidationRemainOwnedByTheCaller() {
        val sql = "SELECT CAST(7 AS UInt16)/CAST(2 AS UInt8) SETTINGS sql_compatible_division_for_integers=1"
        val ast = parseOne(sql, "clickhouse")
        annotateTypes(ast, dialect = Dialects.CLICKHOUSE, expressionMetadata = Dialects.CLICKHOUSE.expressionMetadata + (Div::class to TypingSpec.Returns(DType.DOUBLE)))
        assertTrue((ast as Select).selects.filterIsInstance<Expression>().first().isType(DType.DOUBLE))
        val preserved = parseOne(sql, "clickhouse")
        preserved.find<Div>()!!.typeSlot = DataType(args("this" to DType.DOUBLE, "nullable" to false))
        annotateTypes(preserved, dialect = Dialects.CLICKHOUSE, overwriteTypes = false)
        assertEquals("Float64", type(preserved))
        val cached = parseOne(sql, "clickhouse")
        val annotator = TypeAnnotator(MappingSchema(dialect = Dialects.CLICKHOUSE))
        annotator.annotate(cached)
        assertEquals("UInt16", type(cached))
        (cached as Select).set("settings", (parseOne(sql.replace("integers=1", "integers=0"), "clickhouse") as Select).args["settings"])
        annotator.uncache(cached); annotator.annotate(cached)
        assertEquals("Float64", type(cached))
        annotator.clear(); annotator.annotate(cached)
        assertEquals("Float64", type(cached))
        val stop = IllegalStateException("stop")
        assertSame(stop, runCatching { annotateClickhouseSemanticTypes(parseOne(sql, "clickhouse"), "26.10.2.103") { throw stop } }.exceptionOrNull())
    }
}
