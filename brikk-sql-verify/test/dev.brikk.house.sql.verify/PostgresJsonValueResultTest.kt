package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresJsonValueResultTest {
    @Test
    fun livePostgresPathsScalarTypesDefaultsNullsAndErrorsMatchNativeSql() {
        val container = System.getenv("BRIKK_POSTGRES_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_POSTGRES_TEST_CONTAINER is not configured")
        val client = listOf("psql", "-U", "postgres", "-A", "-P", "footer=off", "-v", "ON_ERROR_STOP=1", "-c")
        for ((expression, expected) in listOf(
            "JSON_VALUE('{\"a\":42}', '$.a')" to "42",
            "JSON_VALUE('{\"a\":{\"b\":42}}', '$.a.b' RETURNING BIGINT)" to "42",
            "JSON_VALUE('{\"my key\":42}', '$.\"my key\"' RETURNING BIGINT)" to "42",
            "JSON_VALUE('[1,2]', 'strict $[1]' RETURNING BIGINT)" to "2",
            "JSON_VALUE('[1,2]', 'strict $[*]' DEFAULT 9 ON ERROR)" to "9",
            "JSON_VALUE('{\"a\":[1,2,3]}', 'lax $.a[*] ? (@ > 2)' RETURNING BIGINT)" to "3",
            "JSON_VALUE('{}', '$.a' RETURNING BIGINT DEFAULT 7 ON EMPTY DEFAULT 9 ON ERROR)" to "7",
            "JSON_VALUE('{\"a\":\"bad\"}', '$.a' RETURNING BIGINT DEFAULT 7 ON EMPTY DEFAULT 9 ON ERROR)" to "9",
            "JSON_VALUE('{\"a\":\"bad\"}', '$.a' RETURNING BIGINT NULL ON EMPTY NULL ON ERROR)" to "<NULL>",
            "JSON_VALUE('null', '$' RETURNING BIGINT)" to "<NULL>",
            "JSON_VALUE(NULL::JSONB, '$' RETURNING BIGINT)" to "<NULL>",
            "JSON_VALUE('\"123.45\"', '$' RETURNING DECIMAL(5, 2))" to "123.45",
            "JSON_VALUE('{\"a\":42}', '$' || '.a')" to "42",
        )) {
            val source = "SELECT COALESCE(($expression)::TEXT,'<NULL>') AS value, PG_TYPEOF($expression) AS type"
            val output = Dialects.POSTGRES.parseOne(source).sql("postgres")
            val native = runCatchupEngineSql(container!!, client, source)
            assertEquals(expected, native.lines()[1].substringBefore('|'), expression)
            assertEquals(native, runCatchupEngineSql(container, client, output), output)
        }
        for (source in listOf("SELECT JSON_VALUE(j,p RETURNING BIGINT) AS value FROM " +
            "(VALUES ('{\"a\":42}'::JSONB,'$.a'::JSONPATH)) AS t(j,p)",
            "SELECT JSON_VALUE('{\"a\":42}', CASE WHEN TRUE THEN '$.a' ELSE '$.b' END) AS value",
            "SELECT JSON_VALUE('\"2020-01-01T01:02:03\"', '$' RETURNING TIMESTAMP WITH TIME ZONE) AS value")) {
            assertEquals(runCatchupEngineSql(container!!, client, source),
                runCatchupEngineSql(container, client, Dialects.POSTGRES.parseOne(source).sql("postgres")))
        }
        for ((expression, state) in listOf(
            "JSON_VALUE('{}', '$.a' RETURNING BIGINT ERROR ON EMPTY ERROR ON ERROR)" to "22035",
            "JSON_VALUE('[1,2]', 'strict $[*]' RETURNING BIGINT ERROR ON ERROR)" to "22034",
            "JSON_VALUE('{\"a\":\"bad\"}', '$.a' RETURNING BIGINT ERROR ON ERROR)" to "22P02",
        )) {
            for (sql in listOf(expression, Dialects.POSTGRES.parseOne(expression).sql("postgres"))) {
                runCatchupEngineSql(container!!, client, "DO $$ BEGIN PERFORM $sql; " +
                    "RAISE EXCEPTION 'unexpected JSON_VALUE success'; EXCEPTION WHEN SQLSTATE '$state' THEN NULL; END $$")
            }
        }
    }
}
