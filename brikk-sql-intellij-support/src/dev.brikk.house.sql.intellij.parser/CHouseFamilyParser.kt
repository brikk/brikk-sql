package dev.brikk.house.sql.intellij.parser

import com.intellij.lang.PsiBuilder
import com.intellij.sql.dialects.clickhouse.CHouseParser
import org.jetbrains.annotations.ApiStatus

/**
 * The platform ClickHouse grammar with its top-level statements kept to the [coreDialect]'s script
 * ranges by a [StatementBoundaryGate]: a statement the grammar would split, such as
 * `INSERT INTO FUNCTION s3(...) PARTITION BY ...` followed by `SELECT` on a new line, becomes one
 * lenient statement, whose `:name` binds are parameters. Subclasses add their own statements in
 * [parseStatement].
 */
@ApiStatus.Experimental
public open class CHouseFamilyParser(coreDialect: String = "clickhouse") : CHouseParser() {
    private val boundaries = StatementBoundaryGate(coreDialect, colonParameters = true)

    final override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean =
        boundaries.parse(builder) { parseStatement(builder, level) }

    /** Parses one statement; by default with the platform grammar. */
    protected open fun parseStatement(builder: PsiBuilder, level: Int): Boolean = super.parseSqlStatement(builder, level)
}
