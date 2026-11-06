package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.*

internal class DuckdbReaderIdentifierResultTest {
    @Test fun conversionModesAffectBindingOnlyAndLiteralPrivateReadersWorkInEveryMode() {
        val root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "reader-identifiers-")
        try {
            val csv = root.resolve("audit.file.csv")
            Files.writeString(csv, "n\n42\n")
            DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
                setProperty("autoload_known_extensions", "false")
                setProperty("autoinstall_known_extensions", "false")
                setProperty("extension_directory", root.resolve("extensions").toString())
            }).use { c ->
                fun execute(sql: String) = c.createStatement().use { it.execute(sql) }
                fun scalar(sql: String) = c.createStatement().use { s ->
                    s.executeQuery(sql).use { r ->
                        assertTrue(r.next())
                        r.getString(1)
                    }
                }
                execute("SET allowed_directories=['${root.toString().replace("'","''")}/']")
                execute("SET enable_external_access=false")
                execute("CREATE SEQUENCE untouched")
                val identifier = "\"${csv.toString().replace("\"","\"\"")}\""
                val literal = "'${csv.toString().replace("'","''")}'"
                var expectedSequence = 1
                for (mode in listOf("DEFAULT", "ENABLE_IMPLICIT_STRING", "DISABLE_IMPLICIT_STRING")) {
                    execute("SET table_function_identifier_conversion='$mode'")
                    for (argument in listOf(identifier, literal)) {
                        val sql = "SELECT n FROM read_csv($argument,header => true)"
                        assertIs<SerializedSql.Parsed>(c.serializeSql(sql))
                        val generated = parseDuckdbNativeOne(sql).sql("duckdb")
                        assertTrue(argument in generated)
                        for (text in listOf(sql, generated)) {
                            if (argument == identifier && mode == "DISABLE_IMPLICIT_STRING") {
                                val error = assertFailsWith<SQLException> { scalar(text) }
                                assertTrue("Binder Error" in error.message.orEmpty())
                            } else assertEquals("42", scalar(text))
                        }
                    }
                    assertIs<SerializedSql.Parsed>(c.serializeSql("SELECT * FROM read_csv(nextval('untouched'),header => true)"))
                    assertEquals((expectedSequence++).toString(), scalar("SELECT nextval('untouched')"))
                }
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
