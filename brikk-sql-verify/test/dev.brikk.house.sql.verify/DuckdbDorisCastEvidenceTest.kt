package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.FindingKind
import dev.brikk.house.sql.shape.Severity
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.certify
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuckdbDorisCastEvidenceTest {
    @Test
    fun pinnedDuckdbResultsAndGeneratedDorisGrammarKeepConditionalFindings() {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("semantics/duckdb-doris-cast.tsv"))
        val vectors = stream.bufferedReader().use { it.readLines().drop(1).map { line -> line.split('\t') } }
        assertEquals(22, vectors.size)
        val doris = assertNotNull(SqlVerifiers.forEngine("doris"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (fields in vectors) {
                assertEquals(5, fields.size)
                val (id, expression, expected) = fields
                val sql = "SELECT $expression"
                val outcome = try {
                    connection.createStatement().use { statement ->
                        statement.executeQuery(sql).use { rows ->
                            assertTrue(rows.next(), id)
                            rows.getString(1) ?: "NULL"
                        }
                    }
                } catch (error: SQLException) {
                    "ERROR"
                }
                assertEquals(expected, outcome, "$id: $sql")
                val report = SqlFragment(sql, "duckdb").certify("doris")
                assertTrue(doris.verify(report.result.sql).accepted, "$id: ${report.result.sql}")
                assertTrue(report.findings.none { it.kind == FindingKind.SEMANTIC_COVERAGE_UNKNOWN }, "$id: ${report.findings}")
                val hazard = report.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD && it.subject == "CAST" }
                assertEquals(Severity.WARNING, hazard.severity, id)
                assertTrue(hazard.detail.startsWith("conditionally-equivalent:"), "$id: ${hazard.detail}")
            }
        }
    }
}
