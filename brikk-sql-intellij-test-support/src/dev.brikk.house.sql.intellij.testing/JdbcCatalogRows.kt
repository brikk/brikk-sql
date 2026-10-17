package dev.brikk.house.sql.intellij.testing

import dev.brikk.house.sql.intellij.catalog.CatalogRow
import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import java.sql.Connection

/** Catalog rows over a plain JDBC [connection], such as an embedded engine, to run a dialect's harvester in tests. */
public class JdbcCatalogRows(private val connection: Connection, private val queryTimeoutSeconds: Int? = null) : CatalogRowSource {
    override fun forEachRow(sql: String, onRow: (CatalogRow) -> Unit) {
        connection.createStatement().use { statement ->
            queryTimeoutSeconds?.let { statement.queryTimeout = it }
            statement.executeQuery(sql).use { rows ->
                while (rows.next()) onRow(CatalogRow(rows::getString))
            }
        }
    }
}
