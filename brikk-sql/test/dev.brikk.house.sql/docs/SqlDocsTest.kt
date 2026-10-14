package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.ENGINE_DOCS
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDocsTest {
    /** The link at `<caret>` in [sql]. */
    private fun at(sql: String, dialect: String = "doris") = SqlDocs.at(sql.replace("<caret>", ""), dialect, sql.indexOf("<caret>"))

    private val doris = "https://doris.apache.org/docs/4.x/sql-manual/"

    @Test
    fun dorisStatementFormsLinkTheirPinnedPages() {
        for ((sql, page) in listOf(
            "CREATE <caret>JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1" to "sql-statements/job/CREATE-JOB/",
            "<caret>create external table t (k INT) ENGINE=hive" to "sql-statements/table-and-view/table/CREATE-TABLE/",
            "CREATE CATALOG<caret> hive PROPERTIES ('type' = 'hms')" to "sql-statements/catalog/CREATE-CATALOG/",
            "INSERT <caret>INTO t SELECT 1" to "sql-statements/data-modification/DML/INSERT/",
            "INSERT <caret>OVERWRITE TABLE t SELECT 1" to "sql-statements/data-modification/DML/INSERT-OVERWRITE/",
            "<caret>EXPORT TABLE t TO 's3://b/p/'" to "sql-statements/data-modification/load-and-export/EXPORT/",
            "SELECT k FROM t INTO <caret>OUTFILE 's3://b/p/'" to "sql-statements/data-modification/load-and-export/OUTFILE/",
            "CREATE ROUTINE <caret>LOAD db.job ON t FROM KAFKA ('kafka_topic' = 'x')" to "sql-statements/data-modification/load-and-export/CREATE-ROUTINE-LOAD/",
            "<caret>SET exec_mem_limit = 1" to "sql-statements/session/variable/SET-VARIABLE/",
            "SET <caret>PASSWORD = PASSWORD('x')" to "sql-statements/account-management/SET-PASSWORD/",
            "/* lead */ CREATE OR REPLACE <caret>VIEW v AS SELECT 1" to "sql-statements/table-and-view/view/CREATE-VIEW/",
            "SELECT 1; REFRESH <caret>CATALOG hive" to "sql-statements/catalog/REFRESH/",
        )) assertEquals(doris + page, at(sql)?.url, sql)
        val job = at("CREATE <caret>JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1")
        assertEquals("CREATE JOB" to (0 until 10), job?.title to job?.let { it.start until it.end })
    }

    @Test
    fun dorisTableFunctionsLinkTheirPagesOnlyInRelationPositions() {
        assertEquals("${doris}sql-functions/table-valued-functions/s3/", at("SELECT * FROM <caret>s3(\"uri\" = \"s3://b/p\", \"format\" = \"csv\")")?.url)
        assertEquals("${doris}sql-functions/table-valued-functions/partition-values/", at("SELECT * FROM partition_<caret>values(\"catalog\" = \"c\")")?.url)
        assertEquals("${doris}sql-functions/table-valued-functions/s3/", at("INSERT INTO s3<caret>(\"uri\" = \"s3://b/p/\") SELECT 1")?.url)
        // A column, or a function of that name outside FROM, is no table function.
        assertNull(at("SELECT <caret>numbers FROM t"))
        assertNull(at("SELECT local<caret>(1) FROM t"))
    }

    @Test
    fun nothingIsOfferedForUnrelatedTokensOrUnknownDialects() {
        for (sql in listOf("SELECT k FROM <caret>t", "SELECT 'CREATE <caret>JOB'", "SELECT 1 -- CREATE <caret>JOB", "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO <caret>t SELECT 1",
            "SELECT k <caret>FROM t", "SELECT `create` FROM t WHERE k = <caret>1")) assertNull(at(sql), sql)
        assertNull(at("CREATE <caret>JOB j", "postgres"))
    }

    /** Every bundled page resolves; skipped offline. */
    @Test
    fun theBundledPagesResolve() {
        val online = runCatching { InetAddress.getByName("doris.apache.org") }.isSuccess && System.getenv("BRIKK_OFFLINE") == null
        if (!online) return println("SqlDocsTest: offline, page check skipped")
        val failures = ENGINE_DOCS.values.flatMap { it.urls() }.mapNotNull { url ->
            val code = runCatching {
                (URI(url).toURL().openConnection() as HttpURLConnection).run {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "brikk-sql docs check")
                    responseCode.also { disconnect() }
                }
            }.getOrDefault(-1)
            "$code $url".takeIf { code != 200 }
        }
        assertEquals(emptyList(), failures)
    }
}
