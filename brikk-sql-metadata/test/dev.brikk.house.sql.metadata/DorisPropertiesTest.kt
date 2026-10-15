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
}
