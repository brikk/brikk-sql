package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.qualify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Consumer contract, not SQLGlot parity. Mirrors Engine SqlAnalyzer.computeOutput/applyTo
 * and ColumnValidation.validateColumns (brikk-house, checked 2026-10-10).
 * Keep these expectations through reference updates: partial shapes are useful evidence,
 * not full schemas; strict validation must not invent ownership. No Engine source dependency.
 */
class EngineAnalysisContractTest {
    private val dialects = listOf("postgres", "duckdb", "doris")

    private val events = Shape(listOf(
        ColumnShape("event_id", "BIGINT", nullable = false),
        ColumnShape("amount", "INT", nullable = false),
        ColumnShape("note", "TEXT", nullable = true),
    ))
    private val catalog = ShapeCatalog(tables = linkedMapOf(
        "public.events" to events,
        "public.unrelated" to Shape.of("secret" to "TEXT"),
    ))

    private fun assertColumns(expected: List<ColumnShape>, actual: Shape, label: String) {
        assertEquals(expected, actual.columns, label)
    }

    /** Same two analysis steps as Engine; do not simplify this to qualification of raw SQL. */
    private fun validateLikeEngine(source: String, dialectName: String, inputs: ShapeCatalog): String {
        val dialect = Dialects.forName(dialectName)
        val standard = SqlFragment(source, dialectName).toStandardSql(inputs = inputs, expandStars = true)
        val schema = MappingSchema(dialect = dialect, allowEmptyTables = true)
        val simple = Regex("[_a-zA-Z][_a-zA-Z0-9]*")
        fun columns(shape: Shape) = shape.columns.associate {
            val name = if (it.quoted || !simple.matches(it.name)) {
                dialect.generate(Identifier(args("this" to it.name, "quoted" to true)))
            } else it.name
            name to it.type
        }
        var depth = 1
        for ((name, shape) in inputs.tables) {
            val table = if ('"' !in name && '`' !in name) {
                val parts = name.split('.').map { Identifier(args("this" to it, "quoted" to false)) }.reversed()
                Table(args("this" to parts[0], "db" to parts.getOrNull(1), "catalog" to parts.getOrNull(2)))
            } else {
                val reader = if ('`' in name) Dialects.MYSQL else Dialects.BASE
                reader.parser().parseIntoTable(reader.tokenize(name), name) as Table
            }
            depth = maxOf(depth, table.parts.size)
            schema.addTable(table, columns(shape), matchDepth = false)
        }
        for ((name, shape) in inputs.slots) {
            schema.addTable((List(depth - 1) { "__slots" } + name).joinToString("."), columns(shape), matchDepth = false)
        }
        val qualified = qualify(dialect.parseOne(standard), dialect = dialect, schema = schema,
            inferSchema = false, validateQualifyColumns = true)
        return dialect.generate(qualified)
    }

    @Test
    fun absentAndUnrelatedCatalogsDoNotTurnShapeDiscoveryIntoStrictValidation() {
        for (dialect in dialects) {
            for (inputs in listOf(ShapeCatalog.EMPTY, ShapeCatalog(mapOf("other" to Shape.of("secret" to "TEXT"))))) {
                assertColumns(listOf(ColumnShape("amount", "UNKNOWN")),
                    SqlFragment("SELECT amount FROM mystery", dialect).outputShape(inputs), "$dialect/$inputs")
                assertColumns(listOf(ColumnShape("*", "UNKNOWN")),
                    SqlFragment("SELECT * FROM mystery", dialect).outputShape(inputs), "$dialect/$inputs")
                if (inputs.tables.isEmpty()) {
                    // Engine first runs tolerant toStandardSql; that already attaches the
                    // sole unknown source's qualifier. Its later strict pass does not prove
                    // that column exists when this table has no column evidence.
                    validateLikeEngine("SELECT amount FROM mystery", dialect, inputs)
                } else {
                    assertFailsWith<OptimizeError>(dialect) {
                        validateLikeEngine("SELECT amount FROM mystery", dialect, inputs)
                    }
                }
            }
        }
    }

    @Test
    fun knownColumnsKeepTheirTypesWhileUnknownProjectionTypesStayUnknown() {
        for (dialect in dialects) {
            val fragment = SqlFragment("SELECT event_id, missing AS optional_value FROM public.events", dialect)
            assertColumns(listOf(ColumnShape("event_id", "BIGINT", false), ColumnShape("optional_value", "UNKNOWN")),
                fragment.outputShape(catalog), dialect)
            assertFailsWith<OptimizeError>(dialect) { validateLikeEngine(fragment.sql, dialect, catalog) }
        }
    }

    @Test
    fun traitMinimumInputsAndConcreteCallSiteInputsRefineTheSameGenericPipe() {
        for (dialect in dialects) {
            val source = "FROM src() |> SELECT *"
            val fragment = SqlFragment(source, dialect)
            val original = fragment.ast.copy()
            val minimum = Shape(listOf(ColumnShape("event_id", "BIGINT", false)))
            val partial = catalog.copy(slots = mapOf("src" to minimum))
            val concrete = catalog.copy(slots = mapOf("src" to events))
            assertColumns(minimum.columns, fragment.outputShape(partial), "$dialect/minimum")
            assertColumns(events.columns, fragment.outputShape(concrete), "$dialect/concrete")
            assertColumns(minimum.columns, fragment.outputShape(partial), "$dialect/recomputed-minimum")
            validateLikeEngine(source, dialect, partial)
            validateLikeEngine(source, dialect, concrete)
            assertEquals(original, fragment.ast, "$dialect analysis must not mutate the runtime SQL AST")
            assertEquals(source, fragment.sql)
            val extended = SqlFragment("FROM src() |> EXTEND event_id AS copied_id", dialect)
            assertColumns(minimum.columns + ColumnShape("copied_id", "BIGINT", false),
                extended.outputShape(partial), "$dialect/partial-extension")
            assertColumns(events.columns + ColumnShape("copied_id", "BIGINT", false),
                extended.outputShape(concrete), "$dialect/concrete-extension")
        }
    }

    @Test
    fun emptyPartialSlotsNeverBorrowColumnsFromSameNamedPhysicalTables() {
        for (dialect in dialects) {
            val inputs = catalog.copy(slots = mapOf("events" to Shape.EMPTY))
            assertColumns(listOf(ColumnShape("*", "UNKNOWN")),
                SqlFragment("FROM events()", dialect).outputShape(inputs), dialect)
            assertColumns(listOf(ColumnShape("event_id", "UNKNOWN")),
                SqlFragment("SELECT event_id FROM events()", dialect).outputShape(inputs), dialect)
            assertFailsWith<OptimizeError>(dialect) {
                validateLikeEngine("SELECT event_id FROM events()", dialect, inputs)
            }
        }
    }

    @Test
    fun schemaLessStageShapesAndAliasesRemainAvailableWithoutValidationOrFabricatedTypes() {
        for (dialect in dialects) {
            val fragment = SqlFragment("FROM mystery |> SELECT CAST(:n AS INT) AS known |> SELECT known |> AGGREGATE COUNT(*) AS n", dialect)
            val stages = fragment.stageShapes()
            assertEquals(4, stages.size, dialect)
            assertColumns(listOf(ColumnShape("*", "UNKNOWN")), stages[0], "$dialect/unknown-head")
            assertColumns(listOf(ColumnShape("known", "INT", nullable = null)), stages[1], "$dialect/typed-projection")
            assertColumns(listOf(ColumnShape("known", "INT", nullable = null)), stages[2], "$dialect/projection")
            assertColumns(listOf(ColumnShape("n", "BIGINT", nullable = false)), stages[3], "$dialect/count")
            assertEquals(stages.last(), fragment.outputShape(), dialect)
            val aliases = SqlFragment("SELECT CAST(:n AS INT) AS known, known AS copied ORDER BY copied", dialect)
            assertColumns(listOf(ColumnShape("known", "INT"), ColumnShape("copied", "INT")),
                aliases.outputShape(), "$dialect/alias-expansion")
        }
    }

    @Test
    fun constantsAndDerivedQueryProjectionsNeedNoPhysicalCatalog() {
        for (dialect in dialects) {
            val source = "WITH q AS (SELECT CAST(7 AS BIGINT) AS id) SELECT id AS chosen FROM q"
            assertColumns(listOf(ColumnShape("chosen", "BIGINT", false)),
                SqlFragment(source, dialect).outputShape(), dialect)
            validateLikeEngine(source, dialect, ShapeCatalog.EMPTY)
        }
    }

    @Test
    fun pipeStagesKeepTheirHeadExtensionProjectionAndAggregateShapeContracts() {
        for (dialect in dialects) {
            val inputs = catalog.copy(slots = mapOf("src" to events))
            val source = "FROM src() |> EXTEND event_id AS next_id |> SELECT next_id |> AGGREGATE COUNT(*) AS n"
            val fragment = SqlFragment(source, dialect)
            val extended = events.columns + ColumnShape("next_id", "BIGINT", false)
            val stages = fragment.stageShapes(inputs)
            assertEquals(4, stages.size, dialect)
            assertColumns(events.columns, stages[0], "$dialect/head")
            assertColumns(extended, stages[1], "$dialect/extend")
            assertColumns(listOf(ColumnShape("next_id", "BIGINT", false)), stages[2], "$dialect/select")
            assertColumns(listOf(ColumnShape("n", "BIGINT", false)), stages[3], "$dialect/aggregate")
            assertEquals(stages.last(), fragment.outputShape(inputs), dialect)
            validateLikeEngine(source, dialect, inputs)
        }
    }

    @Test
    fun strictCheckingRespectsCteAndCorrelatedScopesInsteadOfCatalogColumnUnions() {
        for (dialect in dialects) {
            for (source in listOf(
                "WITH q AS (SELECT event_id AS chosen FROM public.events) SELECT chosen FROM q",
                "SELECT e.event_id FROM public.events AS e WHERE EXISTS " +
                    "(SELECT 1 FROM public.events AS d WHERE d.event_id = e.event_id)",
                "SELECT event_id + 1 AS next_id FROM public.events ORDER BY next_id",
            )) {
                validateLikeEngine(source, dialect, catalog)
                assertTrue(SqlFragment(source, dialect).outputShape(catalog).columns.all { it.type != "UNKNOWN" }, source)
            }
            for (source in listOf(
                "SELECT ghost.event_id FROM public.events",
                "SELECT secret FROM public.events",
                "WITH q AS (SELECT event_id AS chosen FROM public.events) SELECT event_id FROM q",
                "SELECT e.event_id FROM public.events AS e WHERE EXISTS " +
                    "(SELECT 1 FROM public.events AS d WHERE d.missing = e.event_id)",
            )) assertFailsWith<OptimizeError>("$dialect: $source") { validateLikeEngine(source, dialect, catalog) }
        }
    }

    @Test
    fun quotedCatalogIdentityAndSlotColumnsSurviveQualifiedNamespaceBinding() {
        val inputs = ShapeCatalog(
            tables = mapOf("`analytics`.`events`" to Shape.of("physical_only" to "TEXT")),
            slots = mapOf("events" to Shape(listOf(ColumnShape("CaseId", "BIGINT", false, quoted = true)))),
        )
        val source = "SELECT s.\"CaseId\" FROM events() AS s"
        // The current qualified/quoted slot path does not retain a nullability proof;
        // Engine maps unknown nullability to a nullable getter, never a false guarantee.
        assertColumns(listOf(ColumnShape("CaseId", "BIGINT", nullable = null, quoted = true)),
            SqlFragment(source, "postgres").outputShape(inputs), source)
        validateLikeEngine(source, "postgres", inputs)
        assertFailsWith<OptimizeError> {
            validateLikeEngine("SELECT physical_only FROM events()", "postgres", inputs)
        }
    }

    @Test
    fun joinNullabilityAndSetOperationWideningRemainSafeForGeneratedKotlinGetters() {
        for (dialect in dialects) {
            val inputs = ShapeCatalog(mapOf("a" to events, "b" to events))
            val source = "SELECT a.event_id AS left_id, b.event_id AS right_id FROM a LEFT JOIN b ON a.event_id = b.event_id"
            assertColumns(listOf(ColumnShape("left_id", "BIGINT", false), ColumnShape("right_id", "BIGINT", true)),
                SqlFragment(source, dialect).outputShape(inputs), dialect)
            validateLikeEngine(source, dialect, inputs)
            assertColumns(listOf(ColumnShape("x", "BIGINT", true)),
                SqlFragment("SELECT 1 AS x UNION ALL SELECT CAST(2147483648 AS BIGINT) AS x UNION ALL SELECT NULL AS x", dialect)
                    .outputShape(), dialect)
        }
    }

    @Test
    fun unknownActualTypesCannotSatisfyKnownTraitsButKnownSupersetsStillCan() {
        val trait = Shape.of("amount" to "INT")
        val unknown = SqlFragment("SELECT amount FROM mystery", "postgres").outputShape()
        assertEquals(ShapeVerdict.HAS_LESS, trait.compare(unknown, "postgres").verdict)
        val minimum = Shape.of("event_id" to "BIGINT")
        assertEquals(ShapeVerdict.HAS_ADDITIONAL, minimum.compare(events, "postgres").verdict)
        assertEquals(ShapeVerdict.SATISFIES, minimum.compare(Shape.of("event_id" to "BIGINT"), "postgres").verdict)
    }

    @Test
    fun schemalessLineageKeepsUniqueSourcesAndDoesNotInventAmbiguousOwnership() {
        for (dialect in dialects) {
            val unique = SqlFragment("WITH q AS (SELECT amount AS chosen FROM mystery) SELECT chosen FROM q", dialect)
            assertColumns(listOf(ColumnShape("chosen", "UNKNOWN")), unique.outputShape(), dialect)
            assertEquals(mapOf("chosen" to setOf("mystery")), unique.columnDependencies(), dialect)
            assertEquals(mapOf("chosen" to setOf("mystery")), unique.contract().dependencies, dialect)
            val ambiguous = SqlFragment("SELECT amount FROM mystery AS a CROSS JOIN other AS b", dialect)
            assertEquals(mapOf("amount" to setOf("amount")), ambiguous.columnDependencies(), dialect)
            assertEquals(Shape.of("amount" to "UNKNOWN"), ambiguous.outputShape(), dialect)
        }
    }

    @Test
    fun boundSlotContractsKeepInputNamesAndDependenciesAcrossThePipeCteChain() {
        for (dialect in dialects) {
            val inputs = ShapeCatalog(tables = emptyMap(), slots = mapOf("src" to events))
            val source = "FROM src() |> SELECT event_id AS chosen, note"
            val fragment = SqlFragment(source, dialect)
            val contract = fragment.contract(inputs)
            assertEquals(listOf("src"), contract.inputsUsed, dialect)
            assertColumns(listOf(ColumnShape("chosen", "BIGINT", false), ColumnShape("note", "TEXT", true)),
                contract.output, dialect)
            assertEquals(mapOf("chosen" to setOf("src"), "note" to setOf("src")), contract.dependencies, dialect)
            assertEquals(contract.dependencies, fragment.columnDependencies(inputs = inputs), dialect)
        }
    }

    @Test
    fun duckdbComprehensionLocalsSurviveEnginePreparationWithoutTakingSchemaColumnTypes() {
        val source = "SELECT marker, [x FOR x IN items IF x > 0] AS result FROM t"
        val inputs = ShapeCatalog(mapOf("t" to Shape(listOf(
            ColumnShape("marker", "TEXT", nullable = false), ColumnShape("items", "ARRAY<INT>", nullable = true),
            ColumnShape("x", "TEXT", nullable = false),
        ))))
        val fragment = SqlFragment(source, "duckdb")
        val original = fragment.ast.copy()
        for (catalog in listOf(inputs, ShapeCatalog.EMPTY, ShapeCatalog(mapOf("other" to Shape.of("x" to "TEXT"))))) {
            val output = fragment.outputShape(catalog)
            assertEquals(listOf("marker", "result"), output.columns.map { it.name })
            assertEquals(null, output.columns.last().nullable, "do not manufacture non-null comprehension results")
            val standard = fragment.toStandardSql(inputs = catalog, expandStars = true)
            assertTrue("FOR" in standard)
            assertFalse("\"t\".\"x\"" in standard, standard)
        }
        assertEquals(ColumnShape("marker", "TEXT", false), fragment.outputShape(inputs).columns.first())
        validateLikeEngine(source, "duckdb", inputs)
        validateLikeEngine(source, "duckdb", ShapeCatalog.EMPTY)
        assertFailsWith<OptimizeError> {
            validateLikeEngine(source, "duckdb", ShapeCatalog(mapOf("other" to Shape.of("x" to "TEXT"))))
        }
        assertEquals(original, fragment.ast)
    }

    @Test
    fun usingJoinStarPreparationKeepsNativeOrderTypesAndUnresolvedKeys() {
        val a = Shape(listOf(ColumnShape("left_id", "INT", false), ColumnShape("k", "INT", false),
            ColumnShape("left_value", "TEXT", true)))
        val b = Shape(listOf(ColumnShape("k", "BIGINT", false), ColumnShape("right_id", "INT", false),
            ColumnShape("right_value", "TEXT", true)))
        val full = ShapeCatalog(linkedMapOf("a" to a, "b" to b))
        val fragment = SqlFragment("SELECT * FROM a LEFT JOIN b USING (k)", "duckdb")
        val original = fragment.ast.copy()
        assertEquals(listOf("left_id", "k", "left_value", "right_id", "right_value"),
            fragment.outputShape(full).columns.map { it.name })
        assertEquals(ColumnShape("k", "INT", false), fragment.outputShape(full).columns[1])
        assertEquals(true, fragment.outputShape(full).columns[3].nullable)
        for (inputs in listOf(ShapeCatalog.EMPTY, ShapeCatalog(mapOf("a" to a)))) {
            val unknown = SqlFragment("SELECT *, k AS joined_key FROM a RIGHT JOIN b USING (k)", "duckdb")
            val standard = unknown.toStandardSql(inputs = inputs, expandStars = true)
            assertTrue("USING" in standard && "*" in standard, standard)
            assertFalse("\"a\".\"k\" AS \"joined_key\"" in standard, standard)
            validateLikeEngine(unknown.sql, "duckdb", inputs)
        }
        assertEquals(original, fragment.ast)
    }

    @Test
    fun correlatedUnnestProjectionEvidenceSurvivesEnginePreparationWithoutFabricatedNullability() {
        val source = "WITH t AS (SELECT [STRUCT('a' AS email, CAST(2 AS FLOAT64) AS magnitude)] AS items) " +
            "SELECT (SELECT email FROM UNNEST(items)) AS linked_email, " +
            "(SELECT MAX(magnitude) FROM UNNEST(items)) AS gallons FROM t"
        val fragment = SqlFragment(source, "bigquery")
        val original = fragment.ast.copy()
        assertColumns(listOf(ColumnShape("linked_email", "VARCHAR"), ColumnShape("gallons", "DOUBLE")),
            fragment.outputShape(), "computed correlated projection")
        validateLikeEngine(source, "bigquery", ShapeCatalog.EMPTY)
        val slots = ShapeCatalog(tables = emptyMap(), slots = mapOf("src" to Shape.of("items" to "INT64")))
        val computed = SqlFragment("WITH t AS (SELECT [STRUCT('computed' AS email)] AS items FROM src()) " +
            "SELECT (SELECT email FROM UNNEST(items)) AS linked_email FROM t", "bigquery")
        assertColumns(listOf(ColumnShape("linked_email", "VARCHAR")), computed.outputShape(slots), "slot field must not override computation")
        validateLikeEngine(computed.sql, "bigquery", slots)
        assertEquals(original, fragment.ast)
        assertEquals(source, fragment.sql)
    }

    @Test
    fun unknownUnnestComputationsStayUnknownEvenWithSameNamedCatalogEvidence() {
        val inputs = ShapeCatalog(mapOf("tbl" to Shape.of("items" to "ARRAY<STRUCT<email STRING>>")))
        val fragment = SqlFragment("WITH t AS (SELECT MYSTERY(items) AS items FROM tbl) " +
            "SELECT (SELECT email FROM UNNEST(items)) AS linked_email FROM t", "bigquery")
        val original = fragment.ast.copy()
        for (catalog in listOf(ShapeCatalog.EMPTY, inputs)) {
            assertColumns(listOf(ColumnShape("linked_email", "UNKNOWN")), fragment.outputShape(catalog), "unknown computation/$catalog")
            assertFailsWith<OptimizeError> { validateLikeEngine(fragment.sql, "bigquery", catalog) }
        }
        assertEquals(original, fragment.ast)
    }
}
