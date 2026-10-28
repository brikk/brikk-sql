package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DialectMetadataTest {

    private fun metadata(vararg versions: String, default: String = versions.first()) = DialectMetadata(
        "duckdb",
        versions.map { MetadataSnapshot(it, FunctionCatalog(emptyList())) },
        defaultVersion = default,
    )

    private val duckdb = metadata("2.0.0", "1.5.6", default = "1.5.6")

    private fun DialectMetadata.pick(raw: String?) = forServerVersion(raw).let { it.snapshot.engineVersion to it.exact }

    @Test
    fun snapshotsAreOrderedOldestFirst() {
        assertEquals(listOf("1.5.6", "2.0.0"), duckdb.snapshots.map { it.engineVersion })
    }

    @Test
    fun aSnapshotOfTheSameMajorMinorWins() {
        assertEquals("1.5.6" to false, duckdb.pick("v1.5.5"))
        assertEquals("1.5.6" to true, duckdb.pick("v1.5.6"))
        assertEquals("1.5.6" to false, duckdb.pick("v1.5.7"))
    }

    @Test
    fun aPrereleaseServerIsNeverExact() {
        assertEquals("2.0.0" to false, duckdb.pick("v2.0.0-alpha43143"))
    }

    @Test
    fun aServerNewerThanEverySnapshotGetsTheNewest() {
        assertEquals("2.0.0" to false, duckdb.pick("v2.1.0"))
    }

    @Test
    fun aServerBetweenSnapshotsGetsTheNearestOlder() {
        assertEquals("1.4.0" to false, metadata("1.4.0", "2.0.0").pick("v1.5.5"))
    }

    @Test
    fun aServerOlderThanEverySnapshotGetsTheOldest() {
        assertEquals("1.5.6" to false, duckdb.pick("1.4.4"))
    }

    @Test
    fun anUnknownVersionGetsTheDefault() {
        assertEquals("1.5.6" to false, duckdb.pick(null))
        assertEquals("1.5.6" to false, duckdb.pick("garbage"))
        assertEquals("1.5.6" to false, duckdb.pick("v99999999999.1"))
    }

    @Test
    fun singleNumberVersionsSelect() {
        assertEquals("483" to true, metadata("483").pick("483"))
    }

    @Test
    fun theDefaultMustBeASnapshot() {
        assertFailsWith<IllegalArgumentException> { metadata("1.5.6", default = "1.5.5") }
    }

    @Test
    fun bundledDuckdbServesTheDefaultRelease() {
        assertEquals("2.0.0", DUCKDB_METADATA.default.engineVersion)
        assertEquals(DUCKDB_METADATA.default.functions, DUCKDB_FUNCTION_CATALOG)
        assertEquals("1.5.6" to false, DUCKDB_METADATA.pick("v1.5.5"))
    }

    @Test
    fun bundledTrinoServes484WithoutMislabelingOlderServers() {
        assertEquals("484" to true, TRINO_METADATA.pick("484"))
        assertEquals("484" to false, TRINO_METADATA.pick("483"))
        assertEquals(KeywordCategory.RESERVED, TRINO_METADATA.default.keywords!!["JSON_SERIALIZE"]?.category)
    }
}
