package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import kotlin.test.*

class EditorContractTest {
    @Test fun routineAndQuotedSeparators() {
        val sql = "CREATE FUNCTION f(x bigint) RETURNS bigint BEGIN IF x > 0 THEN RETURN 1; END IF; RETURN 0; END; SELECT ';|>'; FROM t |> SELECT x; -- tail"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(4, ranges.size)
        assertTrue(sql.substring(ranges[0].start, ranges[0].end).endsWith("END;"))
        assertFalse(ranges[1].hasPipe)
        assertTrue(ranges[2].hasPipe)
        assertFalse(ranges[3].hasSql)
        assertTrue(ranges.all { it.boundaryError == null })
        assertEquals(sql, ranges.joinToString("") { sql.substring(it.start, it.end) })
    }

    @Test fun opaqueMalformedTailAndUnicode() {
        val sql = "SELECT '😀'; FROM t |> WHERE x = 'unterminated; SELECT 2 |> LIMIT 1"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(2, ranges.size)
        assertEquals(sql.indexOf(';') + 1, ranges[0].end)
        assertTrue(ranges[1].hasPipe)
        assertNotNull(ranges[1].boundaryError)
        assertFalse(SqlScriptSnapshot.parse("SELECT 'broken |> hidden", "trino").statements.single().hasPipe)
    }

    @Test fun duckdbDollarQuotesAndComments() {
        val sql = "SELECT $$;|>$$; /* outer /* ; |> */ inner */ FROM t |> LIMIT 1;"
        val ranges = SqlScriptSnapshot.parse(sql, "duckdb").statements
        assertEquals(2, ranges.size)
        assertFalse(ranges[0].hasPipe)
        assertTrue(ranges[1].hasPipe)
    }

    @Test fun unterminatedDuckdbCommentsMakeTheBoundaryOpaque() {
        for (sql in listOf("FROM t |> LIMIT 1 /* open", "FROM t |> LIMIT 1 /* outer /* nested */")) {
            val range = SqlScriptSnapshot.parse(sql, "duckdb").statements.single()
            assertTrue(range.hasPipe)
            assertNotNull(range.boundaryError)
        }
    }

    @Test fun unterminatedTrailingCommentIsNotTrivia() {
        val ranges = SqlScriptSnapshot.parse("SELECT 1; /* unfinished", "doris").statements
        assertEquals(2, ranges.size)
        assertNull(ranges[0].boundaryError)
        assertTrue(ranges[1].hasSql)
        assertNotNull(ranges[1].boundaryError)
    }

    @Test fun parametersWithoutLowering() {
        val sql = "FROM t |> WHERE x = :value OR y = :value |> SELECT ':not_a_bind' AS s"
        val params = parseParameterOccurrences(sql, "trino")
        assertEquals(listOf("value", "value"), params.map { it.name })
        assertTrue(params.all { sql.substring(it.range.start, it.range.end) == ":value" })
        assertTrue(params.all { sql.substring(it.nameRange!!.start, it.nameRange!!.end) == "value" })
        assertFails { parseParameterOccurrences("FROM t |> BOGUS :x", "trino") }
    }

    @Test fun reservedRowsSlotAndWindowRowsRemainDistinct() {
        for (dialect in listOf("trino", "duckdb", "doris")) {
            val fragment = SqlFragment("SELECT r.code FROM rows() AS r", dialect)
            assertEquals(listOf("rows"), fragment.tableSlots)
            SqlFragment("SELECT sum(x) OVER (ORDER BY y ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t", dialect).ast
        }
    }

    @Test fun parenthesizedControlConditionAndScalarIfDoNotSplitRoutine() {
        val sql = "CREATE FUNCTION f(x bigint) RETURNS bigint BEGIN IF (x > 0) THEN RETURN IF(x > 1, 2, 1); END IF; RETURN 0; END; SELECT 2;"
        val ranges = SqlScriptSnapshot.parse(sql, "trino").statements
        assertEquals(2, ranges.size)
        assertNull(ranges[0].boundaryError)
    }

    @Test fun editorCommandsExposeOperatorsButParserCommandBehaviorIsUnchanged() {
        val tokenizer = dev.brikk.house.sql.parser.Tokenizer(dev.brikk.house.sql.parser.TokenizerConfigs.forName("trino"))
        val sql = "SHOW TABLES |> WHERE x = 1"
        assertTrue(tokenizer.tokenizeForEditor(sql).tokens.any { it.tokenType == dev.brikk.house.sql.parser.TokenType.PIPE_GT })
        assertFalse(tokenizer.tokenize(sql).any { it.tokenType == dev.brikk.house.sql.parser.TokenType.PIPE_GT })
        assertTrue(SqlScriptSnapshot.parse(sql, "trino").statements.single().hasPipe)
        assertFalse(SqlScriptSnapshot.parse("SHOW TABLES LIKE '|>'", "trino").statements.single().hasPipe)
    }

    @Test fun unsupportedProceduralBoundariesAreOpaqueAndColumnNamesAreNotRoutineHeads() {
        val script = "CREATE TABLE t (function bigint); FROM t |> SELECT function;"
        val ranges = SqlScriptSnapshot.parse(script, "trino").statements
        assertEquals(2, ranges.size)
        assertTrue(ranges.all { it.boundaryError == null })
        for (sql in listOf("DELIMITER //; SELECT 1 |> LIMIT 1", "CREATE PROCEDURE p() BEGIN SELECT 1; END; SELECT 1 |> LIMIT 1"))
            assertNotNull(SqlScriptSnapshot.parse(sql, "mysql").statements.single().boundaryError)
    }

    @Test fun dorisSemicolonInsideBracketsIsABoundaryError() {
        val sql = "SELECT f(1; 2); SELECT 3"
        val doris = SqlScriptSnapshot.parse(sql, "doris").statements.single()
        assertEquals(0 to sql.length, doris.start to doris.end)
        assertNotNull(doris.boundaryError)
        val rule = "CREATE RULE r AS ON INSERT TO t DO ALSO (INSERT INTO a VALUES (1); INSERT INTO b VALUES (2)); SELECT 1"
        val postgres = SqlScriptSnapshot.parse(rule, "postgres").statements
        assertEquals(2, postgres.size)
        assertTrue(postgres.all { it.boundaryError == null })
    }

    @Test fun dorisHalfOpenPartitionRangesAreBalanced() {
        val ddl = listOf(
            "CREATE TABLE t (k DATE) PARTITION BY RANGE(k) (PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01'), PARTITION p3 VALUES LESS THAN MAXVALUE) DISTRIBUTED BY HASH(k) BUCKETS 1",
            "CREATE TABLE t (k INT, c VARCHAR(10)) PARTITION BY LIST(c) (PARTITION p1 VALUES IN ('a', 'b'), " +
                "PARTITION p2 VALUES IN (('c'), ('d'))) DISTRIBUTED BY HASH(k) BUCKETS 1",
            "CREATE TABLE t (k DATE) PARTITION BY RANGE(k) (FROM ('2020-01-01') TO ('2020-03-01') INTERVAL 1 MONTH) DISTRIBUTED BY HASH(k) BUCKETS 1",
            "ALTER TABLE t ADD PARTITION IF NOT EXISTS p4 VALUES [('2020-03-01', '1'), (MAXVALUE, MAXVALUE)) (\"replication_num\" = \"1\")",
            "ALTER TABLE t ADD TEMPORARY PARTITIONS FROM ('2020-04-01') TO ('2020-06-01') INTERVAL 1 MONTH",
        )
        for (statement in ddl) {
            val script = "$statement; SELECT :p;"
            val ranges = SqlScriptSnapshot.parse(script, "doris").statements
            assertEquals(listOf(statement.length + 1, script.length), ranges.map { it.end }, statement)
            assertTrue(ranges.all { it.boundaryError == null }, statement)
            assertEquals(PipeLowering.NotPipe, lowerForExecution(statement, "doris"))
            assertEquals(listOf("p"), SqlScriptParameters.parse(script, "doris").occurrences.map { it.name })
        }
    }

    @Test fun halfOpenRangesNeedDorisAndValues() {
        for (sql in listOf("SELECT [(1), (2)); SELECT 3", "INSERT INTO t VALUES [('a'), ('b')]; SELECT 3"))
            assertNotNull(SqlScriptSnapshot.parse(sql, "doris").statements.single().boundaryError, sql)
        for (dialect in listOf("duckdb", "trino", "mysql"))
            assertNotNull(SqlScriptSnapshot.parse("ALTER TABLE t ADD PARTITION p VALUES [(1), (2)); SELECT 3", dialect).statements.single().boundaryError, dialect)
    }

    @Test fun starrocksHalfOpenPartitionRangesAreBalanced() {
        val statement = "CREATE TABLE t (k DATE, v INT) PARTITION BY RANGE(k) (PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
            "PARTITION p2 VALUES LESS THAN MAXVALUE) DISTRIBUTED BY HASH(k) BUCKETS 1"
        val script = "$statement; ALTER TABLE t ADD PARTITION p3 VALUES [('2020-02-01'), ('2020-03-01')); SELECT :p;"
        val ranges = SqlScriptSnapshot.parse(script, "starrocks").statements
        assertEquals(3, ranges.size)
        assertTrue(ranges.all { it.boundaryError == null })
        assertEquals(listOf("p"), SqlScriptParameters.parse(script, "starrocks").occurrences.map { it.name })
        assertNotNull(SqlScriptSnapshot.parse("SELECT [(1), (2)); SELECT 3", "starrocks").statements.single().boundaryError)
    }
}
