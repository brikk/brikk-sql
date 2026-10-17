package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.lang.Language
import org.jetbrains.annotations.ApiStatus

/**
 * One core dialect and the IDE identities that speak it. [marker] is the spelling in a
 * `-- dialect: <marker>` line.
 */
@ApiStatus.Experimental
public data class SqlDialectEntry(
    /** Core dialect name, as accepted by `Dialects.forName`. */
    val coreDialect: String,
    /** Display label. */
    val label: String,
    val marker: String = coreDialect,
    /** IDE SQL language ids. */
    val languageIds: Set<String> = emptySet(),
    /** `Dbms` names. */
    val dbmsIds: Set<String> = emptySet(),
)

/**
 * Core dialects and the platform identities that speak them. Every plugin bundles its own copy of
 * this table, so it is static versioned data, keyed only by platform identities: language ids and
 * `Dbms` names, never another plugin's objects. Identities match exactly; stock platform ones sit
 * next to the plugins' own. `GenericSQL` never maps: many engines share it.
 */
@ApiStatus.Experimental
public object SqlDialectRegistry {
    /** Changes whenever [entries] change. */
    public const val VERSION: Int = 3

    /** Sorted by label. */
    public val entries: List<SqlDialectEntry> = listOf(
        SqlDialectEntry("bigquery", "BigQuery", languageIds = setOf("BigQuery"), dbmsIds = setOf("BIGQUERY")),
        SqlDialectEntry("clickhouse", "ClickHouse", languageIds = setOf("ClickHouseSQL", "ClickHouse"), dbmsIds = setOf("CLICKHOUSE_BRIKK", "CLICKHOUSE")),
        SqlDialectEntry("datafusion", "DataFusion"),
        SqlDialectEntry("doris", "Doris", languageIds = setOf("DorisSQL"), dbmsIds = setOf("DORIS")),
        // Stock DuckDB: DUCKDB on the generic dialect before 263, DUCK with its own DuckDB language from 263.
        SqlDialectEntry("duckdb", "DuckDB", languageIds = setOf("DuckDBSQL", "DuckDB"), dbmsIds = setOf("DUCKDB_BRIKK", "DUCKDB", "DUCK")),
        SqlDialectEntry("hive", "Hive", languageIds = setOf("HiveQL"), dbmsIds = setOf("HIVE")),
        SqlDialectEntry("mysql", "MySQL", languageIds = setOf("MySQL", "MariaDB"), dbmsIds = setOf("MYSQL", "MARIADB")),
        SqlDialectEntry("postgres", "PostgreSQL", languageIds = setOf("PostgreSQL"), dbmsIds = setOf("POSTGRES")),
        SqlDialectEntry("presto", "Presto", dbmsIds = setOf("PRESTO")),
        SqlDialectEntry("spark2", "Spark", languageIds = setOf("SparkSQL"), dbmsIds = setOf("SPARK")),
        SqlDialectEntry("trino", "Trino", languageIds = setOf("TrinoSQL"), dbmsIds = setOf("TRINO_BRIKK", "TRINO")),
    )

    /** The entry of [language] or of the nearest base language that has one. */
    public fun forLanguage(language: Language): SqlDialectEntry? =
        generateSequence(language) { it.baseLanguage }.firstNotNullOfOrNull { forLanguageId(it.id) }

    public fun forLanguageId(id: String): SqlDialectEntry? = entries.firstOrNull { id in it.languageIds }

    public fun forDbms(dbms: Dbms): SqlDialectEntry? = entries.firstOrNull { dbms.name in it.dbmsIds }

    public fun forCoreDialect(name: String): SqlDialectEntry? = entries.firstOrNull { it.coreDialect == name }

    /** The entry a `-- dialect:` line names, ignoring case. */
    public fun forMarker(spelling: String): SqlDialectEntry? = entries.firstOrNull { it.marker.equals(spelling, ignoreCase = true) }
}
