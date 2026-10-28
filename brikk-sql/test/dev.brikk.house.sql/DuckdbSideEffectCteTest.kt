package dev.brikk.house.sql

import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.SqlglotError
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.scope.CatalogKnowledge
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuckdbSideEffectCteTest {
    private fun field(name: String, type: String) = ColumnFact(name, type, nullability = Nullability.NON_NULL,
        origin = ColumnOrigin.PHYSICAL_TABLE, provenance = "physical:$name")
    private val facts = RelationFacts(tables = mapOf(
        "staging" to RelationFact(listOf(field("id", "INT"), field("v", "VARCHAR"))),
        "archive" to RelationFact(listOf(field("id", "INT"), field("v", "VARCHAR"))),
        "orders" to RelationFact(listOf(field("id", "INT"), field("status", "VARCHAR"))),
    ), catalog = CatalogKnowledge.CLOSED)
    private val catalog = ShapeCatalog(facts.tables.mapValues { (_, fact) -> Shape(fact.columns.map { ColumnShape(it.name, it.sqlType!!, false) }) })
    private val updated = "WITH updated AS (UPDATE orders SET status = 'shipped' WHERE status = 'ready' RETURNING id, status AS state) SELECT updated.id, updated.state FROM updated"
    private val copied = "WITH copied AS (COPY staging TO 'never-opened.csv' (FORMAT CSV)) SELECT * FROM copied"

    @Test
    fun writeAndCopyBodiesStayStructuredAndMaterializationHintsAreNotChanged() {
        for (hint in listOf("", "MATERIALIZED ", "NOT MATERIALIZED ")) for (body in listOf(
            "INSERT INTO archive SELECT * FROM staging RETURNING id, v",
            "UPDATE orders SET status = 'shipped' RETURNING *",
            "DELETE FROM staging WHERE id > 0 RETURNING id, v",
            "COPY staging TO 'never-opened.csv' (FORMAT CSV, HEADER)",
            "COPY (SELECT * FROM staging ORDER BY id) TO 'never-opened.parquet' (FORMAT PARQUET, RETURN_STATS)",
        )) {
            val sql = "WITH changed AS $hint($body) SELECT * FROM changed"
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
            val cte = tree.findAll<CTE>().single()
            assertEquals(when (hint) { "MATERIALIZED " -> true; "NOT MATERIALIZED " -> false; else -> null }, cte.args["materialized"], sql)
            if (body.startsWith("COPY")) assertIs<Copy>(cte.thisArg)
            val generated = tree.sql("duckdb")
            assertEquals(generated, Dialects.DUCKDB.parseOne(generated).sql("duckdb"), sql)
        }
    }

    @Test
    fun duckdbRejectsUnsupportedBodyKindsCopyFromAndRecursiveWrites() {
        for (body in listOf(
            "CREATE TABLE forbidden(id INT)", "DROP TABLE staging", "ALTER TABLE staging ADD COLUMN z INT",
            "MERGE INTO archive a USING staging s ON a.id=s.id WHEN MATCHED THEN DELETE",
            "COPY staging FROM 'never-opened.csv' (FORMAT CSV)",
        )) assertFailsWith<ParseError>(body) { Dialects.DUCKDB.parseOne("WITH c AS ($body) SELECT * FROM c") }
        for (body in listOf("INSERT INTO archive VALUES (1,'one') RETURNING *", "UPDATE orders SET status='x' RETURNING *",
            "DELETE FROM staging RETURNING *", "COPY staging TO 'never-opened.csv' (FORMAT CSV)")) {
            assertFailsWith<ParseError>(body) { Dialects.DUCKDB.parseOne("WITH RECURSIVE c AS ($body) SELECT * FROM c") }
        }
        Dialects.DUCKDB.parseOne("WITH RECURSIVE c AS (SELECT 1 AS id UNION ALL SELECT id+1 FROM c WHERE id<3) SELECT * FROM c")
    }

    @Test
    fun returningDefinesCteColumnsWithoutClaimingTheOldValuesOfWrites() {
        val scope = RelationScope.analyze(updated, "duckdb", facts)
        assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
        val output = scope.output!!
        assertEquals(listOf("id", "state"), output.columns.map { it.name })
        assertEquals(listOf("INT", "VARCHAR"), output.columns.map { it.sqlType })
        assertTrue(output.columns.all { it.origin == ColumnOrigin.COMPUTED && it.provenance == null })
        val deleted = RelationScope.analyze("WITH gone AS (DELETE FROM staging RETURNING id, v) SELECT gone.id FROM gone", "duckdb", facts)
        assertTrue(deleted.diagnostics.isEmpty(), deleted.diagnostics.toString())
        assertEquals("physical:id", deleted.output!!.columns.single().provenance)
        val inserted = RelationScope.analyze("WITH read_rows AS (SELECT * FROM staging), ins AS (INSERT INTO archive SELECT * FROM read_rows RETURNING id, v) SELECT ins.v FROM ins", "duckdb", facts)
        assertTrue(inserted.diagnostics.isEmpty(), inserted.diagnostics.toString())
        val insertedOutput = inserted.output!!
        assertEquals("VARCHAR", insertedOutput.columns.single().sqlType)
        assertNull(insertedOutput.columns.single().provenance)
    }

    @Test
    fun noReturningDmlExposesTheVerifiedCountRatherThanTargetFields() {
        for (body in listOf("INSERT INTO archive VALUES (1,'one')", "UPDATE orders SET status='shipped'", "DELETE FROM staging")) {
            val sql = "WITH changed(rows_written) AS ($body) SELECT changed.rows_written FROM changed"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), "$sql: ${scope.diagnostics}")
            assertEquals("BIGINT", scope.output!!.columns.single().sqlType)
            val invalid = RelationScope.analyze("WITH changed AS ($body) SELECT changed.id FROM changed", "duckdb", facts)
            assertEquals(listOf(ScopeProblem.MISSING_COLUMN), invalid.diagnostics.map { it.problem })
        }
    }

    @Test
    fun copyResultContractsRespectFlagsAndKeepUnknownOptionsConservative() {
        val cases = mapOf(
            "FORMAT CSV" to listOf("Count" to "BIGINT"),
            "FORMAT CSV, RETURN_FILES false" to listOf("Count" to "BIGINT"),
            "FORMAT PARQUET, RETURN_FILES" to listOf("Count" to "BIGINT", "Files" to "VARCHAR[]"),
            "FORMAT PARQUET, RETURN_STATS" to listOf("filename" to "VARCHAR", "count" to "UBIGINT", "file_size_bytes" to "UBIGINT",
                "footer_size_bytes" to "UBIGINT", "column_statistics" to "MAP(VARCHAR, MAP(VARCHAR, VARCHAR))",
                "partition_keys" to "MAP(VARCHAR, VARCHAR)", "extra_info" to "MAP(VARCHAR, VARIANT)"),
        )
        for ((options, expected) in cases) {
            val sql = "WITH copied AS (COPY staging TO 'never-opened.parquet' ($options)) SELECT * FROM copied"
            val scope = RelationScope.analyze(sql, "duckdb", facts)
            assertTrue(scope.diagnostics.isEmpty(), scope.diagnostics.toString())
            val output = scope.output!!
            assertEquals(expected, output.columns.map { it.name to it.sqlType }, options)
            assertEquals(Knowledge.FULL, output.knowledge)
        }
        val dynamic = RelationScope.analyze("WITH copied AS (COPY staging TO 'never-opened.csv' (FORMAT CSV, RETURN_FILES \$flag)) SELECT copied.Files FROM copied", "duckdb", facts)
        assertTrue(dynamic.diagnostics.isEmpty(), dynamic.diagnostics.toString())
        assertNull(dynamic.output!!.columns.single().sqlType)
    }

    @Test
    fun returningAliasesAndBodyFieldsCompleteInTheirOwnFrames() {
        fun offered(sql: String): List<String> {
            val caret = sql.indexOf("<caret>")
            return RelationScope.complete(sql.replace("<caret>", ""), "duckdb", facts, caret).items.map { it.name }
        }
        assertEquals(listOf("id", "state"), offered(updated.replace("updated.id, updated.state", "updated.<caret>")))
        assertEquals(listOf("id", "status"), offered("WITH changed AS (UPDATE orders SET status='x' WHERE <caret> RETURNING id) SELECT * FROM changed"))
        assertEquals(listOf("Count", "Files"), offered("WITH copied AS (COPY staging TO 'never-opened.parquet' (FORMAT PARQUET, RETURN_FILES)) SELECT copied.<caret> FROM copied"))
    }

    @Test
    fun shapesUseReturningContractsButUnprovedOptimizerAndLineagePathsRefuse() {
        assertEquals(listOf("orders"), SqlFragment(updated, "duckdb").sourceTables)
        assertEquals(listOf("staging"), SqlFragment(copied, "duckdb").sourceTables)
        assertEquals(setOf("archive", "staging"), SqlFragment("WITH staging AS (INSERT INTO archive SELECT * FROM staging RETURNING id) SELECT id FROM staging", "duckdb").sourceTables.toSet())
        assertEquals(setOf("orders", "staging"), SqlFragment("WITH prior AS (UPDATE orders SET status='x' RETURNING id) DELETE FROM staging USING prior WHERE staging.id=prior.id RETURNING staging.id", "duckdb").sourceTables.toSet())
        val expected = listOf(ColumnShape("id", "INT", false), ColumnShape("state", "TEXT", false))
        // Shape types use the existing DuckDB canonicalization; native/scoped VARCHAR names
        // above are a separate contract, not a reason to change normal read-only shape types.
        assertEquals(expected, SqlFragment("SELECT id,status AS state FROM orders", "duckdb").outputShape(catalog).columns)
        assertEquals(expected, SqlFragment(updated, "duckdb").outputShape(catalog).columns)
        val withSlot = catalog.copy(slots = mapOf("rows_input" to Shape(listOf(ColumnShape("external_id", "INT", false)))))
        for (name in listOf("rows_input", "ROWS_INPUT")) {
            assertEquals(listOf(ColumnShape("external_id", "INT", false)), SqlFragment("WITH changed AS (UPDATE orders SET status='x' RETURNING id) SELECT external_id FROM $name(1)", "duckdb").outputShape(withSlot).columns)
        }
        assertEquals(listOf(ColumnShape("Count", "BIGINT", false)), SqlFragment(copied, "duckdb").outputShape(catalog).columns)
        assertFailsWith<ShapeError> { SqlFragment("WITH gone AS (DELETE FROM not_in_catalog RETURNING *) SELECT * FROM gone", "duckdb").outputShape() }
        for (sql in listOf(updated, copied, "WITH moved AS MATERIALIZED (DELETE FROM staging RETURNING *) INSERT INTO archive SELECT * FROM moved")) {
            assertFailsWith<SqlglotError> { lineageAll(sql, dialect = Dialects.DUCKDB) }
            val tree = Dialects.DUCKDB.parseOne(sql)
            val before = Serde.dump(tree)
            assertFailsWith<OptimizeError> { qualify(tree, dialect = Dialects.DUCKDB) }
            assertEquals(before, Serde.dump(tree), "Refusal must happen before mutation")
            assertFailsWith<UnsupportedError> { SqlFragment(sql, "duckdb").toStandardSql(inputs = catalog, expandStars = true) }
            assertTrue(SqlFragment(sql, "duckdb").toExecutable("duckdb").sql.contains("WITH"))
            for (target in listOf("postgres", "trino", "doris", "clickhouse")) {
                assertFailsWith<UnsupportedError>(target) { SqlFragment(sql, "duckdb").toExecutable(target) }
                assertTrue(SqlFragment(sql, "duckdb").transpileTo(target).unsupportedMessages.any { "CTE" in it }, target)
            }
        }
        assertEquals(mapOf("id" to setOf("staging")), SqlFragment("WITH read_rows AS (SELECT id FROM staging) SELECT id FROM read_rows", "duckdb").columnDependencies(inputs = catalog))
    }
}
