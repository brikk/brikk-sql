package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbWindowCatalogSyntaxTest {
    @Test fun unknownExtensionWindowsKeepTheirCallsPartitionsOrderingAndFrameWithoutBuiltinRewrites() {
        val sql="SELECT brikk_row_number() OVER(PARTITION BY p ORDER BY v DESC NULLS LAST ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS n FROM data"
        val tree=Dialects.DUCKDB.parseOne(sql)
        assertEquals(tree,tree.copy());assertEquals(tree,Serde.load(Serde.dump(tree)))
        assertEquals(1,tree.findAll<Window>().count())
        assertTrue(tree.findAll<Anonymous>().any { it.name.equals("brikk_row_number",true) })
        val rendered=tree.sql("duckdb")
        assertTrue("PARTITION BY" in rendered && "ROWS BETWEEN" in rendered)
        assertEquals(rendered,Dialects.DUCKDB.parseOne(rendered).sql("duckdb"))
        assertTrue(SqlFragment(sql,"duckdb").toExecutable("duckdb").unsupportedMessages.isEmpty())
    }
    @Test fun catalogKindIsNotAnImplementationOrAResultTypeForAnUnmodeledExtensionWindow() {
        val shape=SqlFragment("SELECT brikk_row_number() OVER() AS n","duckdb").outputShape()
        assertEquals("UNKNOWN",shape.columns.single().type)
        val qualified="SELECT system.brikk_scalar.brikk_kind() AS scalar,brikk_kind() OVER() AS win"
        val sql=SqlFragment(qualified,"duckdb").toExecutable("duckdb").sql
        assertTrue(sql.contains("system.brikk_scalar",ignoreCase=true))
        assertEquals(1,Regex("OVER",RegexOption.IGNORE_CASE).findAll(sql).count())
    }
}
