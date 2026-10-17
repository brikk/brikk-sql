package dev.brikk.house.sql.scope

import kotlin.test.Test
import kotlin.test.assertEquals

/** Stars through derived tables and CTEs over physical tables, known or not, report nothing false. */
class RelationScopeStarTest {
    private fun column(name: String) = ColumnFact(name, "INT", origin = ColumnOrigin.PHYSICAL_TABLE)

    private val known = RelationFacts(
        tables = mapOf(
            "ext_catalog.archive_db.facts" to RelationFact(listOf(column("ts"), column("uid"), column("kind"))),
            "shop.sales.orders" to RelationFact(listOf(column("order_id"), column("user_id"), column("amount"), column("status"))),
        ),
        catalog = CatalogKnowledge.CLOSED,
    )

    /** The same tables listed without introspected columns. */
    private val listed = known.copy(tables = known.tables.mapValues { RelationFact(knowledge = Knowledge.UNKNOWN) })

    private val shapes = listOf(
        "WITH recent AS (SELECT * FROM (SELECT FROM_SECOND(ts) AS ts, * EXCEPT(ts), 0 AS src_flag " +
            "FROM ext_catalog.archive_db.facts) AS d) SELECT ts, uid FROM recent",
        "WITH recent AS (SELECT * FROM (SELECT FROM_SECOND(ts) AS ts2, *, 0 AS src_flag FROM ext_catalog.archive_db.facts) AS d) " +
            "SELECT ts2, uid FROM recent",
        "SELECT uid, * FROM (SELECT FROM_SECOND(ts) AS ts2, *, 0 AS src_flag FROM ext_catalog.archive_db.facts) AS d",
        "SELECT e.user_id, e.amount FROM (SELECT * EXCEPT (status) FROM sales.orders) AS e",
        "WITH c AS (SELECT upper(status) AS status, * EXCEPT (status) FROM sales.orders) SELECT status, user_id FROM c",
        "SELECT r.amount, r.user_id FROM (SELECT * REPLACE (amount * 100 AS amount) FROM sales.orders) AS r",
        "WITH a AS (SELECT * FROM sales.orders), b AS (SELECT * FROM a WHERE amount > 0) SELECT b.order_id FROM b",
        "WITH c AS (SELECT * FROM (SELECT * FROM sales.orders) AS u) SELECT c.status FROM c",
        "SELECT x.total, x.user_id, x.tag FROM (SELECT amount * 2 AS total, *, 'k' AS tag FROM sales.orders) AS x",
    )

    @Test
    fun starsReportNothingFalseOverKnownAndUnintrospectedTables() {
        val failures = buildList {
            for (dialect in listOf("doris", "duckdb", "bigquery")) for ((label, facts) in listOf("known" to known, "listed" to listed)) {
                for (sql in shapes) {
                    val scope = RelationScope.analyze(sql, dialect, facts)
                    if (!scope.parsed) add("$dialect $label does not parse: $sql")
                    scope.diagnostics.forEach { add("$dialect $label: ${it.message} in $sql") }
                    if (label == "known") scope.references.filter { it.kind == ReferenceKind.COLUMN && it.resolution != Resolution.RESOLVED }
                        .forEach { add("$dialect: ${it.name} ${it.resolution} in $sql") }
                }
            }
        }
        assertEquals(emptyList(), failures)
    }
}
