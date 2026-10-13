package dev.brikk.house.sql.scope

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.projectRoot
import dev.brikk.house.sql.testResource
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The relation acceptance corpus v2.1 (cases.jsonl and facts.json, copied unchanged from the
 * DuckDB plugin's src/test/resources/relation-corpus at f0c6488, kept out of testResources' sqlglot
 * fixture pin check) against [RelationScope], for
 * every dialect each case is tagged with. Facts are copied as [RelationFacts]; observed results
 * are compared per axis exactly like the plugin harness: references, navigation, diagnostics,
 * caret scope and order, completion (the scope filtered by the typed prefix), core's output
 * order and pipe refusals. [DISPUTED] lists the expectations this analysis disagrees with and why.
 */
class RelationCorpusTest {

    private val facts = Json.parseToJsonElement(testResource("relation-corpus/facts.json")).jsonObject
    private val columns = facts.getValue("columns").jsonObject
    private val shapes = facts.getValue("facts").jsonObject
    private val cases = testResource("relation-corpus/cases.jsonl").lines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }

    /** Each corpus column key's fact, one instance per key, so lineage maps back to its identity. */
    private val columnFacts: Map<String, ColumnFact> = columns.mapValues { (_, value) ->
        val column = value.jsonObject
        ColumnFact(
            column.string("name"), column.stringOrNull("sqlType"), column.stringOrNull("kotlinType"),
            Nullability.valueOf(column.string("nullability")),
            when (val origin = column.string("origin")) {
                "KOTLIN_RELATION" -> ColumnOrigin.DECLARED
                else -> ColumnOrigin.valueOf(origin)
            },
            column.stringOrNull("navigationId"),
        )
    }
    private val identities = IdentityHashMap<ColumnFact, String>().apply {
        for ((key, fact) in columnFacts) put(fact, columns.getValue(key).jsonObject.string("identity"))
    }

    @Test fun duckdb() = check("duckdb")
    @Test fun trino() = check("trino")
    @Test fun doris() = check("doris")

    private fun check(dialect: String) {
        val applicable = cases.filter { case -> case.array("dialects").any { it.jsonPrimitive.content == dialect } }
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for (case in applicable) {
            val id = case.string("id")
            for ((axis, expected, actual) in axes(case, dialect)) {
                if (if (axis in ORDERED || axis.startsWith("order")) expected == actual else matches(expected, actual)) continue
                failures += "$id/$axis"
                report.append("$id/$axis [${case.string("sql")}]\n  expected $expected\n  actual   $actual\n")
            }
        }
        val disputes = DISPUTES.filter { dialect in it.dialects }
        disputes.forEach { report.append("disputed ${it.axes}: ${it.reason}\n") }
        File(projectRoot(), "build/relation-corpus").apply { mkdirs() }.resolve("core-$dialect.txt").writeText(report.toString())
        val disputed = disputes.flatMap { it.axes }
        println("Relation corpus $dialect: ${applicable.size} cases, ${applicable.count { case -> failures.none { it.startsWith(case.string("id") + "/") } }} pass, " +
            "${failures.size} differing axes, ${disputed.size} disputed")
        assertEquals(disputed.sorted(), failures.sorted(), report.toString())
    }

    private fun axes(case: JsonObject, dialect: String): List<Triple<String, JsonElement, JsonElement>> {
        val sql = case.string("sql")
        val input = facts(case)
        val analysis = RelationScope.analyze(sql, dialect, input)
        val axes = ArrayList<Triple<String, JsonElement, JsonElement>>()
        val references = case.array("references").map { it.jsonObject }.filter { it.string("kind") != "OPERATOR" }
        axes += Triple("references",
            JsonArray(references.map { reference(it, it.array("targets").map(::expectedColumn).map(::target)) }),
            JsonArray(references.map { expected ->
                val found = analysis.references.firstOrNull {
                    it.start == expected.int("start") && it.end == expected.int("end") && it.kind.name == expected.string("kind")
                }
                reference(expected, if (found == null) listOf(JsonPrimitive("absent")) else
                    found.columns.takeIf { found.resolution == Resolution.RESOLVED }.orEmpty().map(::observed).map(::target))
            }))
        axes += Triple("navigation",
            JsonArray(references.map { reference ->
                navigation(reference, reference["navigationTargets"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: reference.array("targets").mapNotNull { expectedColumn(it).stringOrNull("navigationId") })
            }),
            JsonArray(references.map { expected ->
                val found = analysis.references.firstOrNull { it.start == expected.int("start") && it.end == expected.int("end") }
                navigation(expected, found?.takeIf { it.resolution != Resolution.UNRESOLVED }?.columns?.mapNotNull { it.provenance }.orEmpty())
            }))
        axes += Triple("diagnostics",
            sorted(case.array("diagnostics").map { it.jsonObject }.map { diagnostic(it.string("kind"), it.int("start"), it.int("end")) }),
            sorted(analysis.diagnostics.map { diagnostic(PROBLEMS.getValue(it.problem), it.start, it.end) }))
        for (caret in case.array("carets").map { it.jsonObject }) {
            val offset = caret.int("offset")
            val completion = RelationScope.complete(sql, dialect, input, offset)
            val position = caret.string("positionKind")
            val scope = completion.items.map { it.column }
            axes += Triple("scope@$offset",
                scope(position, caret.string("knowledge"), caret.array("columns").map(::expectedColumn)),
                scope(completion.position.name, completion.knowledge?.name ?: "NONE", scope.map(::observed)))
            if (caret.string("order") == "SIGNIFICANT") axes += Triple("order@$offset",
                JsonArray(caret.array("columns").map { JsonPrimitive(expectedColumn(it).string("name")) }),
                JsonArray(scope.map { JsonPrimitive(it.name) }))
            val prefix = completion.prefix
            axes += Triple("completion@$offset",
                sorted((caret["completionColumns"] ?: caret.getValue("columns")).jsonArray.map(::expectedColumn)),
                sorted(scope.filter { it.name.startsWith(prefix, ignoreCase = true) }.map(::observed)))
        }
        case["coreOrder"]?.let { order ->
            val inputs = ShapeCatalog(emptyMap(), case.getValue("relations").jsonObject.mapValues { (_, fact) ->
                Shape(shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").map { key ->
                    val column = columnFacts.getValue(key.jsonPrimitive.content)
                    ColumnShape(column.name, column.sqlType ?: "UNKNOWN", when (column.nullability) {
                        Nullability.NON_NULL -> false
                        Nullability.NULLABLE -> true
                        Nullability.UNKNOWN -> null
                    })
                })
            })
            axes += Triple("coreOrder", order, JsonArray(SqlFragment(sql, dialect).outputShape(inputs).names().map(::JsonPrimitive)))
            axes += Triple("outputOrder", order, JsonArray(analysis.output?.columns.orEmpty().map { JsonPrimitive(it.name) }))
        }
        case["coreRefusal"]?.let { refusal ->
            val refused = try {
                SqlFragment(sql, dialect).toExecutable(dialect)
                false
            } catch (_: UnsupportedError) {
                true
            }
            axes += Triple("coreRefusal", refusal, JsonPrimitive(refused))
        }
        return axes
    }

    private fun facts(case: JsonObject): RelationFacts {
        fun relations(key: String) = case.getValue(key).jsonObject.mapValues { (_, fact) ->
            val shape = shapes.getValue(fact.jsonPrimitive.content).jsonObject
            RelationFact(shape.array("columns").map { columnFacts.getValue(it.jsonPrimitive.content) }, Knowledge.valueOf(shape.string("knowledge")))
        }
        return RelationFacts(relations("relations"), relations("physicalTables"), relations("callContracts"),
            if (case.string("catalogKnowledge") == "CLOSED") CatalogKnowledge.CLOSED else CatalogKnowledge.OPEN)
    }

    /** A corpus column tuple `[factKey, visibleRelation, name?, nullability?, origin?, navigationId?, identity?]` with defaults filled in. */
    private fun expectedColumn(value: JsonElement): JsonObject {
        val tuple = value.jsonArray
        val column = columns.getValue(tuple[0].jsonPrimitive.content).jsonObject
        fun override(index: Int, key: String) = tuple.getOrNull(index)?.takeUnless { it == JsonNull } ?: column.getValue(key)
        val origin = override(4, "origin").jsonPrimitive.content
        val sqlType = column.getValue("sqlType")
        return column(
            override(2, "name"), tuple[1], JsonPrimitive(origin), column.getValue("kotlinType"),
            // A computed column's SQL type is asserted only where the corpus states one.
            if (origin == "COMPUTED" && sqlType == JsonNull) JsonPrimitive("*") else sqlType,
            override(3, "nullability"), override(5, "navigationId"),
            if (origin == "COMPUTED") JsonPrimitive("computed") else override(6, "identity"),
        )
    }

    private fun observed(column: ScopeColumn): JsonObject {
        val origin = if (column.origin == ColumnOrigin.DECLARED) "KOTLIN_RELATION" else column.origin.name
        return column(
            JsonPrimitive(column.name), JsonPrimitive(column.relation), JsonPrimitive(origin), nullable(column.displayType),
            nullable(column.sqlType), JsonPrimitive(column.nullability.name), nullable(column.provenance),
            JsonPrimitive(column.source?.column?.let { identities[it] ?: "unmapped ${it.name}" } ?: "computed"),
        )
    }

    private fun column(
        name: JsonElement, relation: JsonElement, origin: JsonElement, kotlinType: JsonElement, sqlType: JsonElement,
        nullability: JsonElement, navigationId: JsonElement, identity: JsonElement,
    ) = JsonObject(linkedMapOf(
        "name" to name, "relation" to relation, "origin" to origin, "kotlinType" to kotlinType,
        "sqlType" to sqlType, "nullability" to nullability, "navigationId" to navigationId, "identity" to identity,
    ))

    private fun scope(position: String, knowledge: String, columns: List<JsonObject>) = JsonObject(linkedMapOf(
        "position" to JsonPrimitive(position), "knowledge" to JsonPrimitive(knowledge), "columns" to sorted(columns),
    ))

    private fun target(column: JsonObject) = JsonObject(column.filterKeys { it in TARGET_FIELDS })

    private fun reference(expected: JsonObject, targets: List<JsonElement>) = JsonObject(linkedMapOf(
        "start" to expected.getValue("start"), "end" to expected.getValue("end"), "targets" to sorted(targets),
    ))

    private fun navigation(expected: JsonObject, targets: List<String>) = JsonObject(linkedMapOf(
        "start" to expected.getValue("start"), "end" to expected.getValue("end"),
        "targets" to JsonArray(targets.distinct().sorted().map(::JsonPrimitive)),
    ))

    private fun diagnostic(kind: String, start: Int, end: Int) =
        JsonObject(linkedMapOf("kind" to JsonPrimitive(kind), "start" to JsonPrimitive(start), "end" to JsonPrimitive(end)))

    /** Structural equality where `*` matches any value and arrays compare as multisets. */
    private fun matches(expected: JsonElement, actual: JsonElement): Boolean = when {
        expected == JsonPrimitive("*") -> true
        expected is JsonObject && actual is JsonObject ->
            expected.keys == actual.keys && expected.all { (key, value) -> matches(value, actual.getValue(key)) }
        expected is JsonArray && actual is JsonArray -> expected.size == actual.size && run {
            val unused = actual.toMutableList()
            expected.all { wanted -> unused.firstOrNull { matches(wanted, it) }?.let(unused::remove) == true }
        }
        else -> expected == actual
    }

    private fun sorted(values: Collection<JsonElement>) = JsonArray(values.sortedBy(JsonElement::toString))

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.array(key: String) = getValue(key).jsonArray

    private companion object {
        val TARGET_FIELDS = setOf("name", "relation", "identity", "navigationId")
        val ORDERED = setOf("coreOrder", "outputOrder", "coreRefusal")
        val PROBLEMS = mapOf(
            ScopeProblem.MISSING_COLUMN to "MISSING",
            ScopeProblem.PARTIAL_CONTRACT to "PARTIAL_CONTRACT",
            ScopeProblem.AMBIGUOUS_COLUMN to "AMBIGUOUS",
            ScopeProblem.UNKNOWN_QUALIFIER to "UNKNOWN_QUALIFIER",
            ScopeProblem.UNKNOWN_TABLE to "UNKNOWN_TABLE",
            ScopeProblem.COLUMN_COUNT_MISMATCH to "COLUMN_COUNT_MISMATCH",
        )

        class Dispute(val dialects: Set<String>, val axes: List<String>, val reason: String)

        private val ALL = setOf("duckdb", "trino", "doris")

        /** Expectations this analysis disagrees with, and why; each axis must differ exactly as listed. */
        val DISPUTES = listOf(
            Dispute(ALL, listOf("references", "scope@31", "completion@31").map { "pipe-limit/$it" } +
                listOf("references", "scope@32", "completion@32").flatMap { listOf("pipe-offset/$it", "pipe-distinct/$it") },
                "The columns are expected through `src`, a qualifier the *-unaliased-qualifier-refused cases reject at the same " +
                    "place (UNKNOWN_QUALIFIER, as core refuses it): a relation that cannot qualify is not visible. Once the unaliased " +
                    "input namespace is not kept across the boundary, the row is anonymous, as after |> SELECT *."),
            Dispute(ALL, listOf("pipe-call-unknown-output/scope@47"),
                "PARTIAL claims known columns of `t`; an unknown table function's output is UNKNOWN, as slot-arguments " +
                    "(`src(1) e`) expects for the same situation in FROM."),
            Dispute(ALL, listOf("scope@79", "completion@79").map { "aggregate-group-expression-alias/$it" } +
                listOf("scope@102", "completion@102").map { "unpivot-include-nulls/$it" },
                "Not modelled: INTEGER for `price+qty` and for the UNPIVOT value of price and qty is the native engine's type; " +
                    "the facts give price and qty no SQL type (only kotlin.Int), and no SQL type is inferred from a display type."),
            Dispute(setOf("duckdb", "doris"), listOf("scope@60", "completion@60").map { "portable-group-expression/$it" },
                "At the same caret, ordinary-group-alias lists the alias `kind` for DuckDB and Doris, whose GROUP BY sees " +
                    "projection aliases; one inventory cannot both list and omit it. Trino, where GROUP BY cannot, passes."),
            Dispute(setOf("doris"), listOf("references", "navigation", "scope@19", "completion@19").map { "quoted-source-alias/$it" },
                "Doris reads double quotes as a string literal (MySQL rules without ANSI_QUOTES), so `\"Odd Alias\".id` is no " +
                    "qualified column; core's Doris dialect generates it back as 'Odd Alias'.id."),
            Dispute(setOf("trino"), listOf("pipe-star-join-duplicate-key/navigation"),
                "An ambiguous reference offers the chooser over its candidates, as join-ambiguous and self-join-same-slot " +
                    "expect; this case states no navigationTargets, so the expectation derives none from its empty targets."),
        )
    }
}
