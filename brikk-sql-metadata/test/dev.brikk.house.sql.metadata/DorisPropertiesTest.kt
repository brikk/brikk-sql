package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DorisPropertiesTest {
    private val lists = DORIS_METADATA.docs!!.properties

    private fun list(title: String) = lists.single { it.title == title }

    @Test
    fun everyPropertiesListOfTheStatementsIsBundled() {
        for (title in listOf(
            "CREATE TABLE properties", "CREATE MATERIALIZED VIEW properties", "CREATE JOB properties", "CREATE ROUTINE LOAD job properties",
            "CREATE ROUTINE LOAD Kafka properties", "EXPORT properties", "EXPORT S3 properties", "OUTFILE properties",
            "CREATE CATALOG properties", "CREATE CATALOG properties (hms)", "CREATE RESOURCE properties", "CREATE RESOURCE properties (s3)",
        )) assertTrue(list(title).keys.isNotEmpty(), title)
    }

    @Test
    fun keysCarryTheirSummaryAndTheReleaseTheyComeFrom() {
        val replication = list("CREATE TABLE properties").keys.definitionOf("replication_num")
        assertEquals("2.0", replication?.since)
        assertTrue(replication?.summary.orEmpty().startsWith("The number of replicas"))
        assertEquals(listOf("SSD", "HDD"), list("CREATE TABLE properties").keys.definitionOf("STORAGE_MEDIUM")?.values)
        // A release a page states wins over the line the sources first name it in.
        assertEquals("4.0.3", DORIS_METADATA.default.tableFunction("s3")?.properties?.definitionOf("enable_mapping_timestamp_tz")?.since)
        assertEquals("2.0", DORIS_METADATA.default.tableFunction("s3")?.properties?.definitionOf("uri")?.since)
        // A Hadoop key Doris passes on has no Doris release.
        assertNull(list("EXPORT HDFS properties").keys.definitionOf("dfs.namenode.kerberos.principal")?.since)
    }

    @Test
    fun aFamilyKeyStandsForEveryKeyItPrefixes() {
        val kafka = list("CREATE ROUTINE LOAD Kafka properties").keys
        assertEquals("property.*", kafka.definitionOf("property.client.id")?.name)
        assertNull(kafka.definitionOf("propertyless"))
        assertNotNull(list("CREATE JOB properties").keys.definitionOf("session.query_timeout"))
    }

    @Test
    fun aCatalogsKeysDependOnItsType() {
        assertEquals("type" to "hms", list("CREATE CATALOG properties (hms)").variant)
        assertTrue("hive.metastore.uris" in list("CREATE CATALOG properties (hms)").keys.map { it.name })
        assertTrue("hms" in list("CREATE CATALOG properties").keys.definitionOf("type")!!.values)
    }

    @Test
    fun aKeysReleaseComesFromTheCodeOwningItsListNotFromAnotherStatement() {
        // user is a JDBC resource key from 2.0; the CDC source of CREATE JOB names it only from 4.0.
        assertEquals("2.0", list("CREATE RESOURCE properties (jdbc)").keys.definitionOf("user")?.since)
        assertEquals("4.0", list("CREATE JOB source properties").keys.definitionOf("user")?.since)
        assertEquals("4.0", list("CREATE JOB properties").keys.definitionOf("session.query_timeout")?.since)
        // The Doris catalog's code never names them, so no release is claimed.
        assertNull(list("CREATE CATALOG properties (doris)").keys.definitionOf("password")?.since)
        // Each table function by its own code: http came in 4.0, s3 before.
        val format = { function: String -> DORIS_METADATA.default.tableFunction(function)?.properties?.definitionOf("format")?.since }
        assertEquals("2.0", format("s3"))
        assertEquals("4.0", format("http"))
    }

    @Test
    fun pagePlaceholdersAndOtherTypesExamplesAreNotKeys() {
        assertTrue(list("CREATE CATALOG properties (jdbc)").keys.none { it.name == "hive.metastore.uris" || it.name == "hosts" })
        val http = DORIS_METADATA.default.tableFunction("http")?.properties.orEmpty()
        assertEquals("http.header.*", http.definitionOf("http.header.Authorization")?.name)
        assertTrue(http.none { it.name == "http.header.xxx" })
        assertTrue(DORIS_METADATA.default.tableFunction("local")?.properties.orEmpty().none { it.name == "json_paths" })
    }

    @Test
    fun anAsyncViewTakesTheTableKeysOfItsDuplicateModelAfterItsOwn() {
        val table = list("CREATE TABLE properties").keys
        val view = list("CREATE MATERIALIZED VIEW properties").keys
        assertEquals("grace_period", view.first().name)
        assertEquals(view.map { it.name }.distinct(), view.map { it.name })
        // An inherited key keeps its summary and values, but not a release before views existed.
        assertEquals(table.definitionOf("storage_medium")?.copy(since = "2.1"), view.definitionOf("storage_medium"))
        assertEquals("2.0", table.definitionOf("replication_num")?.since)
        assertEquals("2.1", view.definitionOf("replication_num")?.since)
        assertEquals("3.0", view.definitionOf("file_cache_ttl_seconds")?.since)
        assertEquals(listOf("V2", "V3", "default"), view.definitionOf("storage_format")?.values)
        // Unique-model keys and dynamic partitions are the table's only.
        for (key in listOf("function_column.sequence_col", "enable_unique_key_merge_on_write", "enable_duplicate_without_keys_by_default")) {
            assertNotNull(table.definitionOf(key), key)
            assertNull(view.definitionOf(key), key)
        }
        assertNull(view.definitionOf("dynamic_partition.enable"))
    }
}
