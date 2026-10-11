package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetByteDependencyTest {
    @Test
    fun bytePayloadsUseLatin1StorageOfActualBytesNotUnicodeTextCodepoints() {
        for ((source, hex) in listOf("b'a'" to "61", "b'Â'" to "c382", "b'😀\\n'" to "f09f98800a",
            "b'\\xff\\x00\\\\'" to "ff005c")) {
            val tree = Dialects.BIGQUERY.parseOne(source) as ByteString
            assertEquals(hex, tree.name.map { it.code.toString(16).padStart(2,'0') }.joinToString(""), source)
            assertEquals(tree, Dialects.BIGQUERY.parseOne(tree.sql("bigquery")))
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertEquals("DECODE('$hex', 'hex')", tree.sql("postgres"))
        }
        val config = Dialects.BIGQUERY.tokenizerConfig.withKeywords(emptyMap()).withCommentPolicy()
        assertTrue(config.byteStringsAreBytes)
    }

    @Test
    fun decimalSuffixesKeywordColumnsAndNativeFunctionNodesKeepTheirIntendedRoles() {
        for ((source, output) in listOf("1.0bd" to "CAST('1.0' AS DECIMAL(1, 0))", "1E3BD" to "CAST('1E3' AS DECIMAL(4, 0))")) {
            assertEquals(output, Dialects.HIVE.parseOne(source).sql("hive"))
        }
        assertEquals("ALTER TABLE t ADD COLUMN if INT", Dialects.POSTGRES.parseOne("ALTER TABLE t ADD COLUMN if INT").sql("postgres"))
        assertEquals("ALTER TABLE t DROP COLUMN if", Dialects.POSTGRES.parseOne("ALTER TABLE t DROP if").sql("postgres"))
        assertEquals("SHOW CREATE TABLE db_name.foo", Dialects.MYSQL.parseOne("SHOW CREATE TABLE foo FROM db_name").sql("mysql"))
        val nested = "ALTER TABLE t ADD COLUMNS (a.b INT COMMENT 'x' AFTER c, d.e.f STRING)"
        assertEquals(nested, Dialects.SPARK.parseOne(nested).sql("spark"))
    }
}
