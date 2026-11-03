package dev.brikk.house.sql.intellij.pipe

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.DatafusionCatalogProfile
import dev.brikk.house.sql.intellij.dialect.DatafusionFamilyDialect
import dev.brikk.house.sql.verify.UnavailableVerifier

/** Explicit Brikk mode. With the project's opt-in off, stock Execute submits native SQL unchanged. */
public open class DatafusionPipeDialect(
    final override val language: DatafusionFamilyDialect,
    private val catalog: DatafusionCatalogProfile,
    private val enabled: (Project) -> Boolean,
) : PipeDialect {
    init { require(language.profile.coreDialect == catalog.product) }
    final override val dialect: String = catalog.product
    override val label: String = "${catalog.productLabel} Brikk PIPE"
    override val notificationGroup: String = catalog.productLabel
    override val vetoProperty: String = "brikk.${catalog.product}.pipes"
    override fun isProjectEnabled(project: Project): Boolean = enabled(project)
    override fun ownsSession(session: DatabaseSession): Boolean = catalog.dataSources.accepts(session.connectionPoint.dbms)
    override fun connectionIdentity(session: DatabaseSession): String = catalog.identity(session.connectionPoint.dataSource).hash
    override fun loweringRefusal(file: PsiFile?): String? {
        if (file == null) return null
        val version = language.editorProfile.serverVersion(file)
        if (version != null && !catalog.metadata.forServerVersion(version).exact)
            return "Brikk PIPE lowering is qualified for ${catalog.metadata.default.engineVersion}, not the connected $version profile."
        val settings = language.editorProfile.parserSettings(file)
        val expected = mapOf("dialect" to if (dialect == "spice") "postgresql" else "generic",
            "enable_ident_normalization" to if (dialect == "spice") "false" else "true",
            "default_null_ordering" to "nulls_max", "parse_float_as_decimal" to "false")
        val mismatch = expected.entries.firstOrNull { (name, value) ->
            settings["datafusion.sql_parser.$name"]?.let { it != value } == true
        }
        return mismatch?.let { "Brikk PIPE lowering is not qualified for datafusion.sql_parser.${it.key}=${settings["datafusion.sql_parser.${it.key}"]}." }
    }
    override val previewNote: String = "Brikk-generated SQL, not native pipe syntax. Native parser verification is unavailable; stage lowering is qualified against the pinned runtime. Execution can invoke functions."
    override val validator: NativeSqlValidator = NativeSqlValidator.of(UnavailableVerifier(dialect, "No parser-only Flight SQL verifier"))
}
