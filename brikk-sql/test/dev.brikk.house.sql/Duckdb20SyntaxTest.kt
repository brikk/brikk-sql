package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Nearest
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class Duckdb20SyntaxTest {
    private fun roundTrip(sql: String): String = Dialects.DUCKDB.parseOne(sql).sql("duckdb")

    @Test
    fun documentedCoreSyntaxRoundTrips() {
        val cases = mapOf(
            "SELECT list_transform([1, 2], x -> x + 1)" to
                "SELECT LIST_TRANSFORM([1, 2], LAMBDA x : x + 1)",
            "SELECT * FROM t FETCH FIRST 2 ROWS ONLY" to "SELECT * FROM t LIMIT 2",
            "SELECT * FROM t OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY" to "SELECT * FROM t LIMIT 10 OFFSET 5",
            "SELECT unnest(tags) AS tag, count(*) AS n FROM t GROUP BY unnest(tags)" to
                "SELECT UNNEST(tags) AS tag, COUNT(*) AS n FROM t GROUP BY UNNEST(tags)",
            "CREATE SCHEMA finance.reports" to "CREATE SCHEMA finance.reports",
            "CREATE TABLE finance.reports.q3 (revenue DECIMAL)" to
                "CREATE TABLE finance.reports.q3 (revenue DECIMAL(18, 3))",
            "CREATE SECURE VIEW v AS SELECT 1" to "CREATE SECURE VIEW v AS SELECT 1",
            "SELECT `odd name` FROM t" to "SELECT \"odd name\" FROM t",
            "SELECT * EXCEPT (secret) FROM t" to "SELECT * EXCLUDE (secret) FROM t",
            "SELECT json_set(doc, '$.x', '2'), json_insert(doc, '$.y', '3'), " +
                "json_replace(doc, '$.z', '4'), json_remove(doc, '$.old') FROM t" to
                "SELECT JSON_SET(doc, '$.x', '2'), JSON_INSERT(doc, '$.y', '3'), " +
                "JSON_REPLACE(doc, '$.z', '4'), JSON_REMOVE(doc, '$.old') FROM t",
            "WITH updated AS (UPDATE orders SET status = 'shipped' RETURNING *) SELECT count(*) FROM updated" to
                "WITH updated AS (UPDATE orders SET status = 'shipped' RETURNING *) SELECT COUNT(*) FROM updated",
            "CONNECT qk" to "CONNECT qk",
            "CONNECT 'postgres://localhost/db'" to "CONNECT 'postgres://localhost/db'",
            "CONNECT 'quack:localhost' (TOKEN 'secret')" to "CONNECT 'quack:localhost' (TOKEN 'secret')",
            "DISCONNECT" to "DISCONNECT",
            "CREATE TRIGGER trg AFTER INSERT ON t INSERT INTO audit VALUES (1)" to
                "CREATE TRIGGER trg AFTER INSERT ON t INSERT INTO audit VALUES (1)",
            "DROP TRIGGER trg ON t" to "DROP TRIGGER trg ON t",
        )
        for ((source, expected) in cases) assertEquals(expected, roundTrip(source), source)
    }

    @Test
    fun nearestJoinsKeepModeCountMetricAndSerde() {
        val cases = listOf(
            "SELECT * FROM q JOIN p NEAREST 3 BY DISTANCE ARRAY_DISTANCE(q.v, p.v)",
            "SELECT * FROM q LEFT JOIN p APPROX NEAREST 2 BY SIMILARITY ARRAY_COSINE_SIMILARITY(q.v, p.v)",
            "SELECT * FROM q INNER JOIN p EXACT NEAREST 1 BY DISTANCE ABS(q.id - p.id)",
            "SELECT * FROM q JOIN p NEAREST BY DISTANCE ABS(q.id - p.id)",
        )
        for (sql in cases) {
            val tree = Dialects.DUCKDB.parseOne(sql)
            assertEquals(sql, tree.sql("duckdb"), tree.find(Join::class)?.args.toString())
            assertNotNull(tree.find(Nearest::class), sql)
            assertEquals(tree, Serde.load(Serde.dump(tree)), sql)
        }
        for (sql in listOf(
            "SELECT * FROM q JOIN p NEAREST 1 DISTANCE ABS(q.id - p.id)",
            "SELECT * FROM q JOIN p NEAREST 1 BY ABS(q.id - p.id)",
            "SELECT * FROM q JOIN p NEAREST 1 BY DISTANCE",
        )) assertFailsWith<ParseError>(sql) { Dialects.DUCKDB.parseOne(sql) }
        assertFailsWith<UnsupportedError> {
            SqlFragment(cases.first(), "duckdb").toExecutable("postgres")
        }
    }
}
