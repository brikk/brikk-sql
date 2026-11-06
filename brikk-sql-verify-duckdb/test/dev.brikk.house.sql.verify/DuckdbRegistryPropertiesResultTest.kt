package dev.brikk.house.sql.verify

import dev.brikk.house.sql.metadata.*
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class DuckdbRegistryPropertiesResultTest {
    @Test fun finalRegistryPropertiesAreObservedWithoutInventingUnavailableContracts() {
        DriverManager.getConnection("jdbc:duckdb:", Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("autoinstall_known_extensions", "false")
        }).use { c ->
            val columns = c.createStatement().use { s -> s.executeQuery("SELECT * FROM duckdb_functions() LIMIT 0").use { r -> (1..r.metaData.columnCount).map(r.metaData::getColumnLabel) } }
            for (missing in listOf("null_handling", "parameter_defaults", "positional_count", "table_columns")) assertFalse(missing in columns, missing)
            val facts = c.createStatement().use { s -> s.executeQuery(DUCKDB_FUNCTION_PROPERTIES.sql).use { r -> buildList {
                while (r.next()) add(DUCKDB_FUNCTION_PROPERTIES.decode(DUCKDB_FUNCTION_PROPERTIES.columns.indices.map { r.getString(it + 1) }))
            } } }
            val random = facts.single {
                it.name == "random"
            }
            assertEquals("VOLATILE", random.stability)
            assertEquals(true, random.hasSideEffects)
            assertTrue(facts.filter { it.name == "abs" }.all { it.stability == "CONSISTENT" && it.hasSideEffects == false })
            assertTrue(facts.filter { it.name == "assert_true" }.all { it.nativeKind == "macro" && it.returnType == null && it.parameterTypes.all { type -> type == null } })
            assertTrue(facts.all { it.database == "system" })
            assertTrue(DUCKDB_METADATA.default.functions.functions.all { it.profile == null }, "stability/side effects are not null-propagation proofs")
        }
    }
    @Test fun typedValueIsAConnectionGetterNotAStaticDefaultOrTheDeclaredInputType() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT typed_value IS NOT NULL,typeof(typed_value),input_type FROM duckdb_settings() WHERE name='threads'").use { r ->
                    assertTrue(r.next())
                    assertTrue(r.getBoolean(1))
                    assertEquals("VARIANT", r.getString(2))
                    assertEquals("BIGINT", r.getString(3))
                }
                s.execute("SET threads=1")
                s.executeQuery("SELECT typed_value::BIGINT FROM duckdb_settings() WHERE name='threads'").use { r ->
                    assertTrue(r.next())
                    assertEquals(1, r.getInt(1))
                }
                s.execute("SET threads=2")
                s.executeQuery("SELECT typed_value::BIGINT FROM duckdb_settings() WHERE name='threads'").use { r ->
                    assertTrue(r.next())
                    assertEquals(2, r.getInt(1))
                }
            }
            assertTrue(DUCKDB_SETTINGS.columns.none { it in setOf("value", "typed_value") })
        }
    }
}
