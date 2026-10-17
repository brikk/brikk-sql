package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostgresJsonValueCatchupTest {
    @Test
    fun nativeJsonPathsRemainLiteralExpressionsAndReturningTypesKeepParameters() {
        for (source in listOf(
            "SELECT JSON_VALUE(j, '$.a' RETURNING BIGINT) FROM t",
            "SELECT JSON_VALUE('[1,2]', 'strict $[1]' RETURNING BIGINT)",
            "SELECT JSON_VALUE('{\"a\":[1,2,3]}', 'lax $.a[*] ? (@ > 2)' RETURNING BIGINT)",
            "SELECT JSON_VALUE('{\"my key\":42}', '$.\"my key\"' RETURNING BIGINT)",
            "SELECT JSON_VALUE('{}', '$.a' RETURNING BIGINT DEFAULT 7 ON EMPTY DEFAULT 9 ON ERROR)",
            "SELECT JSON_VALUE('{\"a\":\"bad\"}', '$.a' RETURNING BIGINT NULL ON EMPTY NULL ON ERROR)",
            "SELECT JSON_VALUE('\"123.45\"', '$' RETURNING DECIMAL(5, 2))",
        )) {
            val tree = Dialects.POSTGRES.parseOne(source)
            val value = tree.findAll<JSONValue>().single()
            assertIs<Literal>(value.args["path"])
            assertIs<DataType>(value.args["returning"])
            assertEquals(source, tree.sql("postgres"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
        }
    }

    @Test
    fun dynamicAndExpressionPathsAreNotReinterpretedAsIdentifiersOrJsonPathAst() {
        for (source in listOf(
            "SELECT JSON_VALUE('{\"a\":42}', '$' || '.a')",
            "SELECT JSON_VALUE('{\"a\":42}', CASE WHEN TRUE THEN '$.a' ELSE '$.b' END)",
            "SELECT JSON_VALUE(j, p) FROM (VALUES (CAST('{\"a\":42}' AS JSONB), CAST('$.a' AS JSONPATH))) AS t(j, p)",
        )) {
            val tree = Dialects.POSTGRES.parseOne(source)
            assertEquals(source, tree.sql("postgres"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
        }
        val tree = Dialects.POSTGRES.parseOne("SELECT JSON_VALUE('42', '$' RETURNING BIGINT)")
        assertEquals("SELECT JSON_VALUE('42', '$' RETURNING BIGINT)", Dialects.POSTGRES.generator().generate(tree))
    }

    @Test
    fun postgresTypedScalarEvidenceComesFromReturningNotSchemaOrPathGuessing() {
        for ((suffix, expected) in listOf("" to "TEXT", " RETURNING BIGINT" to "BIGINT",
            " RETURNING DECIMAL(5, 2)" to "DECIMAL(5, 2)", " RETURNING TIMESTAMP WITH TIME ZONE" to "TIMESTAMPTZ")) {
            val tree = annotateTypes(Dialects.POSTGRES.parseOne("SELECT JSON_VALUE(j, '$.a'$suffix)"), dialect = Dialects.POSTGRES)
            assertEquals(expected, tree.selects.single().type?.sql("postgres"))
        }
    }
}
