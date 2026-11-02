package dev.brikk.house.sql.intellij.catalog

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.metadata.*
import org.duckdb.DuckDBFunctions
import java.nio.file.Files
import java.util.function.IntUnaryOperator

class DuckdbJavaUdfCatalogTest : BasePlatformTestCase() {
    fun testRegistrationOwnedOverlayIsIdentityAndSessionScopedAndNeverPersisted() {
        val root=Files.createTempDirectory("java-udf-catalog-")
        try {
            embeddedDuckdb().use { c ->
                DuckDBFunctions.scalarFunction().use { builder ->
                    builder.withName("audit_java_overlay").withParameter(Int::class.javaObjectType).withReturnType(Int::class.javaObjectType)
                        .withIntFunction(IntUnaryOperator { error("Catalog learning must not invoke a UDF") }).register(c)
                }
                val rows=c.createStatement().use { s -> s.executeQuery(DUCKDB_FUNCTIONS.sql).use { r -> buildList {
                    while(r.next())add(DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { r.getString(it+1) }))
                } } }
                val native=rows.single { it.name=="audit_java_overlay" }
                assertTrue(native.internal)
                // Registration ownership is known by the registering application, not inferred
                // from internal=true. Do not put these rows in a shared disk inventory.
                val def=checkNotNull(DuckdbDump("2.0.0",listOf(native)).toSnapshot().functions[native.name])
                val shared=LiveInventory("2.0.0",emptyList(),signatures=true)
                val session=Any();val identity=ConnectionIdentity.of(listOf("jdbc:duckdb:","instance-a"))
                val other=ConnectionIdentity.of(listOf("jdbc:duckdb:","instance-b"))
                val store=LiveCatalogStore(root)
                store.store("ds",identity,shared)
                store.storeSession(session,"ds",identity,SessionFacts(listOf(LiveFunction(def))))
                assertEquals(listOf(native.name),store.select("ds",identity,session).session.functions.map { it.def.name })
                assertEquals(SessionFacts.NONE,store.select("ds",identity,Any()).session)
                assertEquals(SessionFacts.NONE,store.select("ds",other,session).session)
                assertEquals(SessionFacts.NONE,LiveCatalogStore(root).select("ds",identity,session).session)
                assertNull(DUCKDB_METADATA.default.functions[native.name])
                store.storeSession(session,"ds",identity,SessionFacts.NONE)
                assertEquals(SessionFacts.NONE,store.select("ds",identity,session).session)
            }
        }finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
