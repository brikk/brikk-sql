package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.DORIS_METADATA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlPropertiesTest {
    private val docs = DORIS_METADATA.docs!!

    /** The site at `<caret>` in [sql]. */
    private fun at(sql: String) = sqlPropertyAt(sql.replace("<caret>", ""), docs, sql.indexOf("<caret>"), DORIS_METADATA.default::tableFunction)

    private val manual = "https://doris.apache.org/docs/4.x/sql-manual/sql-statements/"

    @Test
    fun aKeyOfAStatementsPropertiesIsItsListsKey() {
        val site = at("CREATE TABLE t (k INT) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_<caret>num\" = \"1\")")!!
        assertEquals("CREATE TABLE properties", site.title)
        assertEquals("${manual}table-and-view/table/CREATE-TABLE/", site.url)
        assertEquals("replication_num", site.key)
        assertFalse(site.value)
        assertEquals("2.0", site.definition?.since)
        assertEquals("replication_", site.prefix)
        assertTrue(site.quoted)
        val text = "CREATE TABLE t (k INT) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")"
        assertEquals("\"replication_num\"", text.substring(site.start, site.end))
    }

    @Test
    fun aValueIsItsKeysAndAStringBeingTypedCounts() {
        val value = at("CREATE TABLE t (k INT) PROPERTIES (\"storage_medium\" = \"S<caret>\")")!!
        assertTrue(value.value)
        assertEquals("storage_medium", value.key)
        assertEquals(listOf("SSD", "HDD"), value.definition?.values)
        val typing = at("CREATE TABLE t (k INT) PROPERTIES (\"replication_num\" = \"1\", \"stor<caret>")!!
        assertEquals("stor", typing.prefix)
        assertFalse(typing.value)
        assertEquals(setOf("replication_num"), typing.used)
        val bare = at("CREATE TABLE t (k INT) PROPERTIES (<caret>)")!!
        assertNull(bare.key)
        assertFalse(bare.quoted)
    }

    @Test
    fun eachStatementHasItsOwnLists() {
        assertEquals("CREATE ROUTINE LOAD Kafka properties",
            at("CREATE ROUTINE LOAD db.j ON t PROPERTIES (\"format\" = \"json\") FROM KAFKA (\"kafka_<caret>topic\" = \"x\")")?.title)
        assertEquals("CREATE ROUTINE LOAD job properties", at("CREATE ROUTINE LOAD db.j ON t PROPERTIES (\"for<caret>mat\" = \"json\") FROM KAFKA (\"k\" = \"x\")")?.title)
        assertEquals("EXPORT S3 properties", at("EXPORT TABLE t TO \"s3://b/p/\" WITH S3 (\"s3.end<caret>point\" = \"x\")")?.title)
        assertEquals("EXPORT broker properties", at("EXPORT TABLE t TO \"hdfs://p/\" WITH BROKER \"b\" (\"user<caret>name\" = \"x\")")?.title)
        assertEquals("OUTFILE properties", at("SELECT * FROM t INTO OUTFILE \"s3://b/p\" FORMAT AS csv PROPERTIES (\"column_<caret>separator\" = \",\")")?.title)
        assertEquals("CREATE JOB properties", at("CREATE JOB j PROPERTIES (\"session.<caret>\" = \"1\") ON STREAMING DO INSERT INTO t SELECT 1")?.title)
        assertEquals("session.*", at("CREATE JOB j PROPERTIES (\"session.query_<caret>timeout\" = \"1\") ON STREAMING DO INSERT INTO t SELECT 1")?.definition?.name)
    }

    @Test
    fun aCatalogsTypeChoosesItsKeys() {
        val hms = at("CREATE CATALOG c PROPERTIES (\"type\" = \"hms\", \"hive.<caret>\")")!!
        assertEquals("CREATE CATALOG properties (hms)", hms.title)
        assertEquals("https://doris.apache.org/docs/4.x/lakehouse/catalogs/hive-catalog/", hms.url)
        assertTrue(hms.keys.any { it.name == "hive.metastore.uris" } && hms.keys.any { it.name == "type" })
        val untyped = at("CREATE CATALOG c PROPERTIES (<caret>)")!!
        assertEquals("CREATE CATALOG properties", untyped.title)
        assertTrue(untyped.keys.none { it.name == "hive.metastore.uris" })
    }

    @Test
    fun aTableFunctionsArgumentsAreItsKeys() {
        val site = at("SELECT * FROM s3(\"uri\" = \"s3://b/p\", \"for<caret>mat\" = \"csv\")")!!
        assertEquals("s3 properties", site.title)
        assertEquals("https://doris.apache.org/docs/4.x/sql-manual/sql-functions/table-valued-functions/s3/", site.url)
        assertTrue(site.definition?.summary.orEmpty().startsWith("File format"))
    }

    @Test
    fun nothingOutsideAPropertiesList() {
        for (sql in listOf(
            "SELECT \"repl<caret>\" FROM t",
            "CREATE TABLE t (k INT, INDEX i (k) USING INVERTED PROPERTIES (\"par<caret>ser\" = \"english\"))",
            "CREATE TABLE t (k INT) PROPERTIES (\"replication_num\" <caret>= \"1\")",
            "SELECT count(\"a<caret>\") FROM t",
            "INSERT INTO t VALUES (\"x<caret>\")",
        )) assertNull(at(sql), sql)
    }
}
