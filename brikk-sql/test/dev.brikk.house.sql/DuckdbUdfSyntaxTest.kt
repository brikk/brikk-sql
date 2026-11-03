package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import kotlin.test.*

class DuckdbUdfSyntaxTest {
    @Test fun unregisteredScalarAndTableCallsPreserveArgumentsWithoutCatalogBinding() {
        for(sql in listOf("SELECT audit_java(7)","SELECT * FROM audit_java(7,flag => true)",
            "SELECT * FROM audit_java(7,flag := false)","SELECT * FROM custom.audit_java(7,flag => true)")) {
            val tree=parseDuckdbNativeOne(sql)
            assertTrue(tree.findAll<Anonymous>().any { it.name=="audit_java" },sql)
            assertFalse(tree.walk().any { it is Command },sql)
            assertEquals(tree,Serde.load(Serde.dump(tree)))
            val rendered=tree.copy().sql("duckdb")
            assertEquals(rendered,parseDuckdbNativeOne(rendered).sql("duckdb"))
            assertFalse(SqlFragment(sql,"duckdb").toExecutable("duckdb").isRawPassthroughStatement)
        }
        assertNull(DUCKDB_METADATA.default.functions["audit_java"])
    }
}
