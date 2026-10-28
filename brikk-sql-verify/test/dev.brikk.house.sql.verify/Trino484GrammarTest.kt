package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertIs

class Trino484GrammarTest {
    @Test
    fun generated484SyntaxPassesTheNativeParser() {
        val verifier = TrinoVerifier()
        for (source in listOf(
            "SELECT JSON('[1, 2]')",
            "SELECT JSON(payload FORMAT JSON ENCODING UTF16)",
            "SELECT JSON_SERIALIZE(payload RETURNING VARBINARY FORMAT JSON ENCODING UTF32 NULL ON ERROR)",
            "SELECT JSON_SCALAR(1.20)",
            "SELECT JSON_QUERY(j, 'lax $.n' RETURNING JSON WITH ARRAY WRAPPER)",
            "SELECT JSON_VALUE(j, 'lax ${'$'}n' PASSING 1.20 AS \"n\" RETURNING DECIMAL(10, 2))",
            "SELECT n FROM JSON_TABLE(JSON '[{\"n\":1}]', 'lax ${'$'}[*]' COLUMNS (n INTEGER PATH 'lax $.n')) AS jt",
            "SELECT schema.json(x)",
            "SELECT json(x, y)",
            "SELECT INTERVAL '340' DAY(3)",
            "SELECT INTERVAL '1.123456789012' SECOND(13, 12)",
            "SELECT CAST(x AS INTERVAL DAY(9) TO SECOND(12))",
            "SELECT (t1 - t2) DAY TO SECOND(6)",
            "CREATE VIEW IF NOT EXISTS v AS SELECT 1",
            "COMMENT ON MATERIALIZED VIEW v IS NULL",
            "ALTER MATERIALIZED VIEW v EXECUTE optimize(file_size_threshold => '10MB') WHERE x = 1",
            "GRANT SELECT ON TABLE t TO USER Alice",
            "ALTER TABLE t SET AUTHORIZATION USER Alice",
            "CREATE SCHEMA s AUTHORIZATION ROLE Admin",
            "SELECT * FROM NEAREST (FROM items WHERE active MATCH distance < 10)",
        ) + listOf("CURRENT ROW", "GROUP", "TIES", "NO OTHERS").map {
            "SELECT SUM(x) OVER (ORDER BY k ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE $it) FROM t"
        }) {
            assertIs<VerifyResult.Accepted>(verifier.verify(source), source)
            val generated = parseOne(source, "trino").sql("trino")
            assertIs<VerifyResult.Accepted>(verifier.verify(generated), generated)
        }
    }
}
