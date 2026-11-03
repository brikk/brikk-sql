package dev.brikk.house.sql.intellij.dialect

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx

/** Keep PG's grammar tokens and special forms, but never use its ordinary function signatures as engine facts. */
internal class DatafusionGrammarFunctions(private val stock: SqlLanguageDialectEx.BuiltinFunctions) : SqlLanguageDialectEx.BuiltinFunctions {
    override fun get(name: String?): BuiltinFunction? = if (keep(name)) stock.get(name) else null
    override fun contains(name: String?): Boolean = keep(name) && stock.contains(name)
    override fun byMatcher(matcher: PrefixMatcher): Iterable<BuiltinFunction> = stock.byMatcher(matcher).filter { keep(it.name) }
    override fun forTokens(): Collection<BuiltinFunction> = stock.forTokens()
    override fun typeMethods(type: String, array: Boolean): SqlLanguageDialectEx.BuiltinFunctions? = stock.typeMethods(type, array)?.let(::DatafusionGrammarFunctions)

    private fun keep(name: String?): Boolean = name?.uppercase() in FORMS
    private companion object {
        val FORMS = setOf("CAST", "TRY_CAST", "EXTRACT", "TRIM", "POSITION", "SUBSTRING", "OVERLAY",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP")
    }
}
