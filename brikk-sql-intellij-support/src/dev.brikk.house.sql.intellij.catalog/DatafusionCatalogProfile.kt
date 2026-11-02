package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.metadata.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Consumers pass their registered DBMS and stable resolver. A DataFusion embedding must opt in to this contract. */
public open class DatafusionCatalogProfile(
    final override val dbms: Dbms,
    public val product: String = "datafusion",
    final override val dataSources: DialectDataSourceResolver = DialectDataSourceResolver(dbms),
) : CatalogProfile, LiveCatalogProfile {
    init { require(product in setOf("datafusion", "spice")) }
    override val productLabel: String = if (product == "spice") "Spice" else "DataFusion"
    override val metadata: DialectMetadata = if (product == "spice") SPICE_METADATA else DATAFUSION_METADATA
    override val harvester: LiveCatalogHarvester = if (product == "spice") SpiceCatalogHarvester() else DatafusionCatalogHarvester()
    override val trigger: HarvestTrigger = HarvestTrigger.EVERY_CONSOLE_CONNECT
    override val sessionScopedInventory: Boolean = true
    override val mergePolicy: LiveMergePolicy = LiveMergePolicy.REPLACE_NAMES
    override val persistenceId: String = "brikk-$product-flight-v1"
    override val deadline: Duration = 30.seconds
    override val quoteChars: String = "\""
    override val levels: List<ObjectKind> = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)
    override val maxDeepenDepth: Int = 3
    override val usesCurrentNamespace: Boolean = true
    // An arbitrary embedding may add transactions. Keep the common transaction guard enabled.
    override val clientTransactions: Boolean = true

    /** Included in cache identity; consumers can supply their embedding/build/configuration revision. */
    public open fun embeddingProfile(dataSource: LocalDataSource): String = "information-schema-v1"

    override fun identity(dataSource: LocalDataSource): ConnectionIdentity = ConnectionIdentity.of(listOf(
        product, dbms.name, dataSource.url, dataSource.username, embeddingProfile(dataSource),
        dataSource.databaseVersion.version,
        dataSource.connectionProperties.entries.map { it.key.toString() to it.value.toString() }.sortedBy { it.first }.toString(),
        dataSource.driverProperties.toSortedMap().toString(),
    ))

    /** Observe execution, not editor changes. Out-of-band hot reload uses the manual refresh action. */
    public val statementDetector: StatementHeadDetector = StatementHeadDetector(listOf(
        "CREATE [OR REPLACE] [TEMP|TEMPORARY] [EXTERNAL] TABLE|VIEW|FUNCTION|SCHEMA|DATABASE",
        "DROP TABLE|VIEW|FUNCTION|SCHEMA|DATABASE", "ALTER TABLE|VIEW|FUNCTION|SCHEMA|DATABASE",
        "SET", "RESET", "USE", "PREPARE", "DEALLOCATE",
    ).map { StatementRule(it, StatementEffect.FunctionInventory(sessionFacts = true)) }, product)
}

public class SpiceCatalogProfile(dbms: Dbms, dataSources: DialectDataSourceResolver = DialectDataSourceResolver(dbms)) :
    DatafusionCatalogProfile(dbms, "spice", dataSources)
