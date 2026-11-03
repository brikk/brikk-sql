package dev.brikk.house.sql.intellij.dialect

import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.dialects.postgres.PgTokens

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
public abstract class DatafusionFamilyDialect(public val profile: DatafusionIdeProfile) : PgFamilyDialect(profile.languageId) {
    /** Override with a stable profile supplying copied connection facts and its cached version. */
    public open val editorProfile: DatafusionEditorProfile by lazy { DatafusionEditorProfile(this) }
    override val builtins: EngineBuiltins by lazy { EngineBuiltins(editorProfile, editorProfile) }
    override fun createTokensHelper(): TokensHelper = TokensHelper(PgTokens::class.java,
        DatafusionGrammarFunctions(SqlFunctionsUtil.loadFunctionDefinition(PgDialect.INSTANCE)))
}
