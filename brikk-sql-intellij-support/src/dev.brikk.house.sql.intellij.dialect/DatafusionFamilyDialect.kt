package dev.brikk.house.sql.intellij.dialect

/** Consumer identities agreed for the independent DataFusion and Spice plugins. No transport aliases. */
public enum class DatafusionIdeProfile(
    public val coreDialect: String,
    public val languageId: String,
    public val dbmsId: String,
) {
    DATAFUSION("datafusion", "DataFusionSQL", "DATAFUSION_BRIKK"),
    SPICE("spice", "SpiceSQL", "SPICE_BRIKK"),
}

/**
 * PostgreSQL PSI support for one native profile. The consumer supplies its own Dbms and registers
 * its language, parser definition and highlighter. Construct one instance per consumer classloader.
 * Native syntax is checked by DatafusionFamilyParser, not certified by the inherited PG grammar.
 */
public abstract class DatafusionFamilyDialect(public val profile: DatafusionIdeProfile) : PgFamilyDialect(profile.languageId)
