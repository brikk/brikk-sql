package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class AlterDependencyResultTest {
    @Test
    fun liveMysqlMixedAndOptionsOnlyAltersRetainTheirSchemas() {
        val container = System.getenv("BRIKK_MYSQL_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_MYSQL_TEST_CONTAINER is not configured")
        val client = listOf("mysql", "--default-character-set=utf8mb4", "-uroot", "-B", "-e")
        val run = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        for ((index, source) in listOf("ALTER TABLE t ADD COLUMN a INT, DROP COLUMN b, ADD COLUMN c INT",
            "ALTER TABLE t ENGINE=InnoDB, ADD COLUMN a INT, COMMENT='hi', ADD COLUMN c INT",
            "ALTER TABLE t COMMENT='hi' AUTO_INCREMENT=7", "ALTER TABLE t DROP b").withIndex()) {
            val output = Dialects.MYSQL.parseOne(source).sql("mysql")
            fun run(which: String, alteration: String): String = runCatchupEngineSql(container!!, client,
                "CREATE DATABASE alter_${which}_${run}_$index; USE alter_${which}_${run}_$index; " +
                    "CREATE TABLE t(id INT AUTO_INCREMENT PRIMARY KEY,b INT); $alteration; SHOW CREATE TABLE t")
            assertEquals(run("source", source), run("output", output), output)
        }
    }

    @Test
    fun livePostgresMixedAltersRetainTheirColumnsAndTypes() {
        val container = System.getenv("BRIKK_POSTGRES_TEST_CONTAINER")
        assumeTrue(!container.isNullOrBlank(), "BRIKK_POSTGRES_TEST_CONTAINER is not configured")
        val client = listOf("psql", "-U", "postgres", "-A", "-q", "-P", "footer=off", "-v", "ON_ERROR_STOP=1", "-c")
        val source = "ALTER TABLE t ADD COLUMN a INT, DROP COLUMN b, ALTER COLUMN id TYPE BIGINT"
        val output = Dialects.POSTGRES.parseOne(source).sql("postgres")
        val run = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        fun run(which: String, alteration: String): String = runCatchupEngineSql(container!!, client,
            "CREATE SCHEMA alter_${which}_$run; SET search_path TO alter_${which}_$run; CREATE TABLE t(id INT,b INT); $alteration; " +
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='alter_${which}_$run' ORDER BY ordinal_position")
        assertEquals(run("source", source), run("output", output), output)
    }
}
