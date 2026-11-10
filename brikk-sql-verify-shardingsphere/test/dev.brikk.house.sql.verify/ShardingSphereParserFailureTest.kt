package dev.brikk.house.sql.verify

import java.util.concurrent.CancellationException
import org.apache.shardingsphere.sql.parser.api.CacheOption
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine
import org.apache.shardingsphere.sql.parser.exception.SQLParsingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

internal class ShardingSphereParserFailureTest {
    @Test
    fun allFourPinnedDialectParsersReportSyntaxErrorsAsSqlParsingException() {
        for ((engine, dialect) in listOf("postgres" to "PostgreSQL", "mysql" to "MySQL", "hive" to "Hive", "clickhouse" to "ClickHouse")) {
            val parser = SQLParserEngine(dialect, CacheOption(128, 1024))
            for (sql in listOf("SELECT 1 +", "SELECT (", "nonsense statement")) {
                val failure = assertFailsWith<SQLParsingException>("$dialect: $sql") { parser.parse(sql, false) }
                val result = ShardingSphereVerifier.forEngine(engine)!!.verify(sql)
                assertEquals(VerifyResult.Rejected(VerifyError(failure.message!!)), result, "$dialect: $sql")
            }
        }
    }

    @Test
    fun onlyTheIdentifiedSyntaxExceptionIsMappedToARejection() {
        val syntax = SQLParsingException("bad syntax")
        assertIs<VerifyResult.Rejected>(shardingSphereParseResult { throw syntax })
        for (failure in listOf(IllegalStateException("parser bug"), CancellationException("edited"))) {
            assertSame(failure, assertFailsWith<Exception> { shardingSphereParseResult { throw failure } })
        }
    }
}
