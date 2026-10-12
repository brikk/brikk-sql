package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.TreePatternNode
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry
import dev.brikk.house.sql.intellij.recoverEditorOperation
import org.jetbrains.annotations.ApiStatus

/** Which data source serves a file, and why. */
@ApiStatus.Experimental
public sealed interface DataSourceResolution {
    public val dataSource: LocalDataSource?

    /** The file's valid console is attached to it. */
    public data class Console(override val dataSource: LocalDataSource) : DataSourceResolution

    /** The file's SQL resolution scope (its own, a directory's or the project's) names it alone. */
    public data class FileMapping(override val dataSource: LocalDataSource) : DataSourceResolution

    /** It is the project's only data source of the dbms. */
    public data class OnlyDataSource(override val dataSource: LocalDataSource) : DataSourceResolution

    /** The scope or the project has several; none is guessed. */
    public data class Ambiguous(val candidates: List<LocalDataSource>) : DataSourceResolution {
        override val dataSource: LocalDataSource? get() = null
    }

    public data object NotFound : DataSourceResolution {
        override val dataSource: LocalDataSource? get() = null
    }
}

/**
 * Finds the data source of [dbms] that serves a file, from the first of: the file's valid console, the
 * file's SQL resolution scope (Database Tools' "SQL Resolution Scopes"), and, with
 * [onlyDataSourceFallback], the project's single data source of the dbms. Injected files resolve
 * through their host. A data source qualifies when its dbms is [dbms] or speaks the same dialect in
 * [SqlDialectRegistry], such as the stock `TRINO` for a plugin's own Trino dbms.
 *
 * Lookups that fail resolve to nothing; cancellation propagates.
 */
@ApiStatus.Experimental
public class DialectDataSourceResolver(
    public val dbms: Dbms,
    private val onlyDataSourceFallback: Boolean = true,
) {
    private val aliases: Set<String> = SqlDialectRegistry.forDbms(dbms)?.dbmsIds.orEmpty() + dbms.name

    public fun accepts(candidate: Dbms): Boolean = candidate.name in aliases

    public fun resolve(file: PsiFile): DataSourceResolution {
        val host = hostFile(file)
        validConsole(file, host)?.let { return DataSourceResolution.Console(it.dataSource) }
        val candidates = recoverEditorOperation {
            LocalDataSourceManager.getInstance(file.project).dataSources.filter { accepts(it.dbms) }
        }.getOrDefault(emptyList())
        val mapped = recoverEditorOperation { mappedDataSourceIds(file, host) }.getOrDefault(emptySet())
        val inScope = candidates.filter { it.uniqueId in mapped }
        if (inScope.isNotEmpty()) return inScope.choose(DataSourceResolution::FileMapping)
        return if (onlyDataSourceFallback) candidates.choose(DataSourceResolution::OnlyDataSource) else DataSourceResolution.NotFound
    }

    /**
     * The current namespace of the file's valid console, outermost first (for example catalog, then
     * schema); empty without a console of the dbms.
     */
    public fun consoleNamespace(file: PsiFile): List<String> {
        val console = validConsole(file, hostFile(file)) ?: return emptyList()
        return recoverEditorOperation {
            generateSequence(console.currentNamespace) { it.parent }.mapNotNull { path -> path.name?.takeIf(String::isNotBlank) }.toList().asReversed()
        }.getOrDefault(emptyList())
    }

    private fun validConsole(file: PsiFile, host: VirtualFile): JdbcConsole? = recoverEditorOperation {
        JdbcConsoleProvider.getValidConsole(file.project, host)?.takeIf { accepts(it.dataSource.dbms) }
    }.getOrNull()

    private fun hostFile(file: PsiFile): VirtualFile =
        InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file).originalFile.viewProvider.virtualFile

    /** Data sources a scope names positively; "all data sources" names none. */
    private fun mappedDataSourceIds(file: PsiFile, host: VirtualFile): Set<String> {
        val scope = SqlResolveMappings.getInstance(file.project).getMapping(host)
        val dataSources = scope?.root?.getGroup(SqlImportUtil.DATA_SOURCE) ?: return emptySet()
        return dataSources.children.orEmpty().mapNotNull { it?.naming as? TreePatternNode.PositiveNaming }
            .flatMapTo(HashSet()) { naming -> naming.names.orEmpty().mapNotNull { it?.name } }
    }

    private fun List<LocalDataSource>.choose(resolved: (LocalDataSource) -> DataSourceResolution): DataSourceResolution = when (size) {
        0 -> DataSourceResolution.NotFound
        1 -> resolved(single())
        else -> DataSourceResolution.Ambiguous(this)
    }
}
