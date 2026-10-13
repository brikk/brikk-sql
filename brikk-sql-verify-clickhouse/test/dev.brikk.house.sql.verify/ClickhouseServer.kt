package dev.brikk.house.sql.verify

import com.clickhouse.jdbc.ClickHouseDriver
import java.sql.Connection
import java.util.Properties
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The ClickHouse server the server tests run on: `BRIKK_CLICKHOUSE_URL`, such as
 * `jdbc:clickhouse://127.0.0.1:18123/default` (user `default`, no password), started with
 * `tools/clickhouse-verify-server.sh`. Without it those tests are skipped.
 */
internal object ClickhouseServer {
    val url: String? = System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank)

    fun require(): String = url.also { assumeTrue(it != null, "BRIKK_CLICKHOUSE_URL is not set") } ?: error("unreachable")

    fun open(url: String = require()): Connection =
        ClickHouseDriver().connect(url, Properties().apply { setProperty("user", "default"); setProperty("password", "")
            // The driver's LZ4 framing fails on some server responses; verification answers are tiny.
            setProperty("compress", "false")
            setProperty("decompress", "false")
        }) ?: error("no connection for $url")

    fun verifier(url: String = require()): ParseOnlyClickhouseVerifier =
        ParseOnlyClickhouseVerifier({ JdbcClickhouseQueryConnection(open(url)) })

    fun Connection.run(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    fun Connection.scalar(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
    }
}
