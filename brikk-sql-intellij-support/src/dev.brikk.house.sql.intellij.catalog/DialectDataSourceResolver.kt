package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.TreePatternNode
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlDataSourceMappings
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry
import dev.brikk.house.sql.intellij.recoverEditorOperation

/** Which data source serves a file, and why. */
public sealed interface DataSourceResolution {
    public val dataSource: LocalDataSource?

    /** The file's valid console is attached to it. */
    public data class Console(override val dataSource: LocalDataSource) : DataSourceResolution

    /**
     * The file's SQL resolution scope (its own, a directory's or the project's) names it alone, or a
     * `sql.dataSourceProvider` names it for the file itself, such as one block of a polyglot file.
     */
    public data class FileMapping(override val dataSource: LocalDataSource) : DataSourceResolution

    /** The file has no console or scope, and this is the project's only data source of the dbms itself. */
    public data class OnlyDataSource(override val dataSource: LocalDataSource) : DataSourceResolution

    /** The scope or the project has several; none is guessed. */
    public data class Ambiguous(val candidates: List<LocalDataSource>) : DataSourceResolution {
        override val dataSource: LocalDataSource? get() = null
    }

    /**
     * Nothing qualifies, the file is bound to a data source of another dbms or excludes some by scope,
     * or a lookup failed.
     */
    public data object NotFound : DataSourceResolution {
        override val dataSource: LocalDataSource? get() = null
    }
}

/**
 * Finds the data source of [dbms] that serves a file. An explicit association decides: the file's
 * valid console, else its SQL resolution scope (Database Tools' "SQL Resolution Scopes") when that
 * names data sources. Between the two, a data source that a `sql.dataSourceProvider` names for the
 * file itself decides, as Database Tools resolves that file: SQL injected into one block of a
 * polyglot file serves that block's connection, not the host file's. A console or scope that names
 * only data sources of another dbms, or that excludes data sources ("all except ..."), resolves to nothing rather than to another source. Only a
 * file with neither falls back, with [onlyDataSourceFallback], to the project's single data source of
 * [dbms] itself. Consoles and scopes also accept dbms that speak the same dialect in
 * [SqlDialectRegistry], such as the stock `TRINO` for a plugin's own Trino dbms. Injected files
 * resolve through their host.
 *
 * Lookups that fail resolve to nothing; cancellation propagates.
 */
public class DialectDataSourceResolver(
    public val dbms: Dbms,
    private val onlyDataSourceFallback: Boolean = true,
) {
    private val aliases: Set<String> = SqlDialectRegistry.forDbms(dbms)?.dbmsIds.orEmpty() + dbms.name

    /** Whether a console or scope bound to [candidate] serves files of [dbms]. */
    public fun accepts(candidate: Dbms): Boolean = candidate.name in aliases

    public fun resolve(file: PsiFile): DataSourceResolution = recoverEditorOperation {
        val host = hostFile(file)
        val project = file.project
        JdbcConsoleProvider.getValidConsole(project, host)?.let { console ->
            return@recoverEditorOperation console.dataSource.takeIf { accepts(it.dbms) }?.let(DataSourceResolution::Console)
                ?: DataSourceResolution.NotFound
        }
        val dataSources = LocalDataSourceManager.getInstance(project).dataSources
        provided(file, dataSources)?.let { return@recoverEditorOperation it }
        when (val scope = scopeOf(file, host)) {
            Scope.Unscoped -> Unit
            Scope.Excluding -> return@recoverEditorOperation DataSourceResolution.NotFound
            is Scope.Naming -> return@recoverEditorOperation dataSources
                .filter { it.uniqueId in scope.ids && accepts(it.dbms) }.choose(DataSourceResolution::FileMapping)
        }
        if (!onlyDataSourceFallback) return@recoverEditorOperation DataSourceResolution.NotFound
        dataSources.filter { it.dbms.name == dbms.name }.choose(DataSourceResolution::OnlyDataSource)
    }.getOrDefault(DataSourceResolution.NotFound)

    /**
     * The current namespace of the file's valid console, outermost first (for example catalog, then
     * schema); empty without a console of the dbms.
     */
    public fun consoleNamespace(file: PsiFile): List<String> = recoverEditorOperation {
        val console = JdbcConsoleProvider.getValidConsole(file.project, hostFile(file))?.takeIf { accepts(it.dataSource.dbms) }
        generateSequence(console?.currentNamespace) { it.parent }.mapNotNull { path -> path.name?.takeIf(String::isNotBlank) }.toList().asReversed()
    }.getOrDefault(emptyList())

    /** The session of the file's valid console of the dbms, if any. */
    public fun consoleSession(file: PsiFile): DatabaseSession? = recoverEditorOperation {
        JdbcConsoleProvider.getValidConsole(file.project, hostFile(file))?.takeIf { accepts(it.dataSource.dbms) }?.session
    }.getOrNull()

    /** The connections a `sql.dataSourceProvider` names for [file], or null when none names any. */
    private fun provided(file: PsiFile, dataSources: List<LocalDataSource>): DataSourceResolution? {
        val ids = SqlDataSourceMappings.getInstance(file.project).getProvidedDataSources(file).mapTo(HashSet()) { it.uniqueId }
        if (ids.isEmpty()) return null
        return dataSources.filter { it.uniqueId in ids && accepts(it.dbms) }.choose(DataSourceResolution::FileMapping)
    }

    private fun hostFile(file: PsiFile): VirtualFile =
        InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file).originalFile.viewProvider.virtualFile

    private sealed interface Scope {
        /** No scope, or all data sources. */
        data object Unscoped : Scope

        /** "All except ...", or a naming this resolver cannot evaluate. */
        data object Excluding : Scope

        data class Naming(val ids: Set<String>) : Scope
    }

    private fun scopeOf(file: PsiFile, host: VirtualFile): Scope {
        val scope = SqlResolveMappings.getInstance(file.project).getMapping(host)
        val namings = scope?.root?.getGroup(SqlImportUtil.DATA_SOURCE)?.children.orEmpty().mapNotNull { it?.naming }
        val everything = namings.filterIsInstance<TreePatternNode.NegativeNaming>().map { it.names.orEmpty().isEmpty() }
        return when {
            namings.isEmpty() || everything.isNotEmpty() && everything.all { it } -> Scope.Unscoped
            namings.all { it is TreePatternNode.PositiveNaming } -> Scope.Naming(namings.flatMapTo(HashSet()) { naming -> naming.names.orEmpty().mapNotNull { it?.name } })
            else -> Scope.Excluding
        }
    }

    private fun List<LocalDataSource>.choose(resolved: (LocalDataSource) -> DataSourceResolution): DataSourceResolution = when (size) {
        0 -> DataSourceResolution.NotFound
        1 -> resolved(single())
        else -> DataSourceResolution.Ambiguous(this)
    }
}
