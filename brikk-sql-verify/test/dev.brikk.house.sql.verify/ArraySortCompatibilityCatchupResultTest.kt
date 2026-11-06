package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.parser.parseOne
import java.sql.DriverManager
import kotlin.test.*

class ArraySortCompatibilityCatchupResultTest {
    @Test fun nativeNullOrdersValuesAndTypesSurviveCopySerdeAndGeneration() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            fun value(sql: String): Pair<String, String?> = s.executeQuery("SELECT $sql").use { r ->
                assertTrue(r.next()); r.metaData.getColumnTypeName(1) to r.getString(1)
            }
            for (sessionOrder in listOf("ASC", "DESC")) {
                s.execute("SET default_order = '$sessionOrder'")
                for (head in listOf("LIST_REVERSE_SORT", "ARRAY_REVERSE_SORT")) {
                    for (array in listOf("[3,NULL,1]", "[]::INT[]", "[NULL]::INT[]", "NULL::INT[]")) {
                        for (nulls in listOf("NULLS FIRST", "NULLS LAST")) {
                            val original = "$head($array,'$nulls')"
                            val tree = parseOne(original, "duckdb")
                            val expected = value(original)
                            for (form in listOf(tree, tree.copy(), Serde.loadExpression(Serde.dump(tree)))) {
                                assertEquals(expected, value(Dialects.DUCKDB.generate(form, sourceDialect = "duckdb")), original)
                            }
                        }
                    }
                }
            }
            s.execute("SET default_order = 'ASC'")
            assertEquals("[null, 3, 1]", value("LIST_REVERSE_SORT([3,NULL,1],'NULLS FIRST')").second?.lowercase())
        } }
    }
}
