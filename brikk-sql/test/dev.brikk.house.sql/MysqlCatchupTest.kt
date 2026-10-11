package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MysqlCatchupTest {
    @Test
    fun commentBoundariesAndEscapeProfilesAreDialectSpecificAndCopySafe() {
        assertEquals(listOf(TokenType.SELECT,TokenType.NUMBER,TokenType.DASH,TokenType.DASH,TokenType.NUMBER),
            Dialects.MYSQL.tokenize("SELECT 1--2").map { it.tokenType })
        assertEquals(listOf(TokenType.SELECT,TokenType.NUMBER), Dialects.MYSQL.tokenize("SELECT 1 -- comment\r,2").map { it.tokenType })
        for (dialect in listOf(Dialects.DORIS, Dialects.STARROCKS)) {
            assertEquals(listOf(TokenType.SELECT,TokenType.NUMBER), dialect.tokenize("SELECT 1--2").map { it.tokenType })
            assertEquals(listOf(TokenType.SELECT,TokenType.NUMBER,TokenType.COMMA,TokenType.NUMBER),
                dialect.tokenize("SELECT 1--comment\r,2").map { it.tokenType })
        }
        for (suffix in listOf("", " ", "\t", "\u001f", "\u007f")) {
            assertEquals(2, Dialects.MYSQL.tokenize("SELECT 1 --$suffix").size)
        }
        assertEquals(2, Dialects.CLICKHOUSE.tokenize("SELECT 1--comment\r,2").size)
        val escaped = Dialects.MYSQL.parseOne("SELECT '\\0\\Z\\a\\f\\v\\q\\%\\_'").selects.single() as Literal
        assertEquals("\u0000\u001aafvq\\%\\_", escaped.name)
        val copy = Dialects.MYSQL.tokenizerConfig.withKeywords(mapOf("BRIKK_TEST" to TokenType.VAR))
        assertTrue(copy.dashCommentRequiresBoundary && copy.commentsTerminateAtNewlineOnly && copy.dropUnknownEscapes)
        val sql = "SELECT '😀\\n' -- comment\rignored\n, 2"
        for (token in Dialects.MYSQL.tokenize(sql)) assertTrue(token.start <= token.end && token.end < sql.length)
    }

    @Test
    fun insertValueSynonymIsScopedToInsertSourcesAndKeepsAllRows() {
        for (source in listOf("INSERT INTO t VALUE (1)", "INSERT INTO t(a) value (1),(2)",
            "INSERT INTO value(value) VALUE ('VALUE')", "INSERT INTO t VALUE ((SELECT value FROM value))")) {
            val tree = Dialects.MYSQL.parseOne(source)
            assertIs<Values>(tree.args["expression"])
            assertEquals(tree, Dialects.MYSQL.parseOne(tree.sql("mysql")))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
        }
        assertEquals("SELECT value FROM value", Dialects.MYSQL.parseOne("SELECT value FROM value").sql("mysql"))
    }

    @Test
    fun charsetSignedDropsAndIndexOptionsRetainTheirScopes() {
        for ((source, expected) in listOf(
            "CREATE TABLE t (a BIGINT SIGNED,b DECIMAL(5,2) SIGNED)" to "CREATE TABLE t (a BIGINT, b DECIMAL(5, 2))",
            "ALTER TABLE t DROP KEY k" to "ALTER TABLE t DROP INDEX k",
            "ALTER TABLE t DROP CHECK c" to "ALTER TABLE t DROP CHECK c",
            "CREATE TABLE t (a VARCHAR(5) ASCII,b VARCHAR(5) UNICODE)" to "CREATE TABLE t (a VARCHAR(5) CHARACTER SET latin1, b VARCHAR(5) CHARACTER SET ucs2)",
            "CREATE TABLE t(a VARCHAR(5) CHAR SET utf8mb4) CHARSET=utf8mb4 ENGINE=InnoDB" to
                "CREATE TABLE t (a VARCHAR(5) CHARACTER SET utf8mb4) CHARACTER SET=utf8mb4 ENGINE=InnoDB",
            "CREATE TABLE t(a INT) ENGINE=InnoDB, COMMENT='c' ROW_FORMAT=DYNAMIC" to
                "CREATE TABLE t (a INT) ENGINE=InnoDB COMMENT='c' ROW_FORMAT=DYNAMIC",
            "CREATE TABLE t(a INT) KEY_BLOCK_SIZE=8" to "CREATE TABLE t (a INT) KEY_BLOCK_SIZE=8",
        )) assertEquals(expected, Dialects.MYSQL.parseOne(source).sql("mysql"), source)
        val key = Dialects.MYSQL.parseOne("ALTER TABLE t ADD PRIMARY KEY(a) KEY_BLOCK_SIZE=8 COMMENT 'key'")
        assertEquals(2, (key.findAll<PrimaryKey>().single().args["options"] as List<*>).size)
        assertEquals(emptyList<Any>(), key.args["options"])
        for (dialect in listOf(Dialects.DORIS, Dialects.STARROCKS)) {
            val native = "CREATE TABLE t (a INT) PROPERTIES ('replication_num'='1')"
            assertTrue("PROPERTIES" in dialect.parseOne(native).sql(dialect.name))
        }
    }

    @Test
    fun weightStringAndCharacterSetCastsKeepLengthsAndNativeOptions() {
        for (source in listOf("SELECT WEIGHT_STRING('ab')", "SELECT WEIGHT_STRING('1' AS CHAR(1))", "SELECT WEIGHT_STRING('ab' AS BINARY(4))")) {
            val tree = Dialects.MYSQL.parseOne(source)
            assertIs<WeightString>(tree.selects.single())
            assertEquals(source, tree.sql("mysql"))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
        }
        val extra = Dialects.MYSQL.parseOne("SELECT weight_string('ab',0,3,0)").selects.single()
        assertIs<Anonymous>(extra)
        assertEquals("weight_string", extra.name)
        for ((source, expected) in listOf(
            "SELECT CAST('ab' AS CHAR(4) CHARSET binary)" to "SELECT CAST('ab' AS CHAR(4) CHARACTER SET binary)",
            "SELECT CONVERT('abc',CHAR(2) CHARACTER SET latin1)" to "SELECT CAST('abc' AS CHAR(2) CHARACTER SET latin1)",
        )) assertEquals(expected, Dialects.MYSQL.parseOne(source).sql("mysql"))
    }

    @Test
    fun nativeIfAndDateConversionsRemainDistinguishableFromCaseAndDatetimeArithmetic() {
        for (dialect in listOf(Dialects.MYSQL, Dialects.DORIS, Dialects.STARROCKS)) {
            assertEquals("SELECT IF(a, 1, 2)", dialect.parseOne("SELECT IF(a,1,2)").sql(dialect.name))
            assertEquals("SELECT IF(a, 1, NULL)", Dialects.PRESTO.parseOne("SELECT IF(a,1)").sql(dialect.name))
            assertEquals("SELECT CASE WHEN a THEN 1 ELSE 2 END", dialect.parseOne("SELECT CASE WHEN a THEN 1 ELSE 2 END").sql(dialect.name))
        }
        for (function in listOf("DATE_ADD", "DATE_SUB")) {
            val source = "SELECT $function(DATE('2024-01-01 12:34:56'), INTERVAL 1 HOUR)"
            assertEquals("SELECT $function(DATE('2024-01-01 12:34:56'), INTERVAL '1' HOUR)",
                Dialects.MYSQL.parseOne(source).sql("mysql"))
        }
    }
}
