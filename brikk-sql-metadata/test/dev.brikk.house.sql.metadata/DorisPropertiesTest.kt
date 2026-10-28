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
    fun release415AddsComputeBindingsAnalyzerAndEmbeddingProperties() {
        for (title in listOf("CREATE MATERIALIZED VIEW properties", "CREATE ROUTINE LOAD job properties")) {
            assertEquals("4.1.5", list(title).keys.definitionOf("compute_group")?.since)
        }
        assertEquals(listOf("true", "false"), list("CREATE INDEX properties").keys.definitionOf("norms")?.values)
        assertTrue(list("CREATE INVERTED INDEX ANALYZER properties").keys.definitionOf("tokenizer")!!.values
            .containsAll(listOf("ik_smart", "ik_max_word")))
        assertEquals("type" to "ai", list("CREATE RESOURCE properties (ai)").variant)
        assertTrue("ai" in list("CREATE RESOURCE properties").keys.definitionOf("type")!!.values)
        for (prefix in listOf("ai.embed.", "ai.embed.mm.")) {
            for (suffix in listOf("endpoint", "provider_type", "model_name", "api_key")) {
                assertEquals("4.1.5", list("CREATE RESOURCE properties (ai)").keys.definitionOf(prefix + suffix)?.since)
            }
        }
    }

    @Test
    fun supplementedListsNeverDuplicateKeys() {
        for (list in lists) assertEquals(list.keys.size, list.keys.map { it.name.lowercase() }.distinct().size, list.title)
    }
}
