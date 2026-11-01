package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class DuckdbUpstreamSortIdentifierResultTest {
    @Test fun sortTableByNameInsertKeepsNativeBindingAndRowsWithoutReplayingWrites() {
        val sql="INSERT INTO s.sort BY NAME SELECT ? AS n"
        val forms=listOf(sql,SqlFragment(sql,"duckdb").toExecutable("duckdb").sql,
            Serde.loadExpression(Serde.dump(parseDuckdbNativeOne(sql))).sql("duckdb"))
        for(form in forms)DriverManager.getConnection("jdbc:duckdb:",Properties().apply {
            setProperty("enable_external_access","false");setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
        }).use { c ->
            c.createStatement().use { it.execute("CREATE SCHEMA s");it.execute("CREATE TABLE s.sort(n INTEGER)") }
            assertIs<VerifyResult.Unverified>(c.serializeSql(form).toParseOnlyResult(form))
            c.prepareStatement(form).use { p -> assertEquals(1,p.parameterMetaData.parameterCount);p.setInt(1,7);assertEquals(1,p.executeUpdate()) }
            c.createStatement().use { s -> s.executeQuery("SELECT n,count(*) OVER() FROM s.sort").use { r ->
                assertTrue(r.next());assertEquals(7,r.getInt(1));assertEquals(1,r.getInt(2));assertFalse(r.next())
            } }
        }
    }
}
