package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.hasSideEffectingCte
import dev.brikk.house.sql.ast.hasKeyedCte
import dev.brikk.house.sql.ast.isTriggerDdl
import dev.brikk.house.sql.ast.isDuckdbExtensionManagement
import dev.brikk.house.sql.ast.isDuckdbExternalResourceStatement
import dev.brikk.house.sql.ast.isDuckdbRecursiveViewDefinition
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.hasDuckdbCopyLayout
import dev.brikk.house.sql.dialects.isDuckdbNameShow

// Import aliases: the rule functions share names with qualify()'s Boolean options
// (which mirror Python's keyword arguments).
import dev.brikk.house.sql.optimizer.isolateTableSelects as isolateTableSelectsRule
import dev.brikk.house.sql.optimizer.qualifyColumns as qualifyColumnsRule
import dev.brikk.house.sql.optimizer.quoteIdentifiers as quoteIdentifiersRule
import dev.brikk.house.sql.optimizer.validateQualifyColumns as validateQualifyColumnsRule

/**
 * Port of sqlglot/optimizer/qualify.py — rewrite the AST to have normalized and
 * qualified tables and columns. This step is necessary for all further optimizations.
 *
 * Example:
 *   schema = {"tbl": {"col": "INT"}}
 *   qualify(parseOne("SELECT col FROM tbl"), schema = schema).sql()
 *   => SELECT "tbl"."col" AS "col" FROM "tbl" AS "tbl"
 */
/**
 * Upstream-shaped qualification: resolve against evidence, never infer an unknown
 * source's columns. For best-effort analysis use the compatibility overload with
 * an explicit [inferSchema] policy. This overload follows SQLGlot #8552.
 */
fun <E : Expression> qualify(
    expression: E,
    dialect: Dialect? = null,
    db: Any? = null,
    catalog: Any? = null,
    schema: Any? = null,
    expandAliasRefs: Boolean = true,
    expandStars: Boolean = true,
    isolateTables: Boolean = false,
    qualifyColumns: Boolean = true,
    allowPartialQualification: Boolean = false,
    validateQualifyColumns: Boolean = true,
    quoteIdentifiers: Boolean = true,
    identify: Boolean = true,
    canonicalizeTableAliases: Boolean = false,
    onQualify: ((Table) -> Unit)? = null,
    sql: String? = null,
): E = qualify(
    expression, dialect, db, catalog, schema, expandAliasRefs, expandStars,
    inferSchema = false, isolateTables, qualifyColumns, allowPartialQualification,
    validateQualifyColumns, quoteIdentifiers, identify, canonicalizeTableAliases, onQualify, sql,
)

/**
 * Compatibility entry point: true retains legacy inference, false selects upstream
 * behavior, null retains the historical empty-schema automatic policy. Defaults and
 * parameter order are retained for existing JVM callers and Kotlin default bridges.
 * New source calls that omit inferSchema select the shorter upstream-shaped overload.
 */
// brikk extension (docs/brikk-extensions.md #34): preserve the published inference API.
fun <E : Expression> qualify(
    expression: E,
    dialect: Dialect? = null,
    db: Any? = null,
    catalog: Any? = null,
    schema: Any? = null,
    expandAliasRefs: Boolean = true,
    expandStars: Boolean = true,
    inferSchema: Boolean? = null,
    isolateTables: Boolean = false,
    qualifyColumns: Boolean = true,
    allowPartialQualification: Boolean = false,
    validateQualifyColumns: Boolean = true,
    quoteIdentifiers: Boolean = true,
    identify: Boolean = true,
    canonicalizeTableAliases: Boolean = false,
    onQualify: ((Table) -> Unit)? = null,
    sql: String? = null,
): E {
    val resolvedDialect = dialect ?: Dialects.BASE
    if (expression.isDuckdbNameShow()) throw OptimizeError("SHOW resolution requires runtime catalog/show_behavior, not SELECT qualification")
    if (resolvedDialect.name == "duckdb" && expression.hasDuckdbCopyLayout())
        throw OptimizeError("COPY layout options bind against output columns; automatic qualification is not modeled")
    if (expression is dev.brikk.house.sql.ast.DuckdbExplain) throw OptimizeError("Qualify an EXPLAIN child explicitly; automatic wrapper rewriting is not modeled")
    if (expression.isDuckdbExternalResourceStatement()) throw OptimizeError("External-resource options and handles are not SELECT qualification inputs")
    if (expression.isDuckdbExtensionManagement()) throw OptimizeError("Extension/repository management is not a SELECT qualification input")
    if (expression.isDuckdbRecursiveViewDefinition()) throw OptimizeError("Recursive view self-reference qualification is not modeled")
    if (expression.isTriggerDdl()) throw OptimizeError("Trigger definitions are not SELECT optimizer inputs")
    if (expression is dev.brikk.house.sql.ast.SessionConnect || expression is dev.brikk.house.sql.ast.SessionDisconnect) {
        throw OptimizeError("Session routing is not a query to qualify")
    }
    if (resolvedDialect.name == "duckdb" && expression.hasKeyedCte()) {
        throw OptimizeError("Automatic qualification across recursive USING KEY state is not modeled")
    }
    if (resolvedDialect.name == "duckdb" && expression.hasSideEffectingCte()) {
        throw OptimizeError("Automatic qualification across side-effecting CTEs is not modeled")
    }
    val resolvedSchema = ensureSchema(schema, dialect = dialect)

    preserveComprehensionIdentifierCase(expression, resolvedDialect)

    var result: E = normalizeIdentifiers(
        expression,
        dialect = resolvedDialect,
        storeOriginalColumnIdentifiers = true,
    )
    result = qualifyTables(
        result,
        db = db,
        catalog = catalog,
        dialect = resolvedDialect,
        onQualify = onQualify,
        canonicalizeTableAliases = canonicalizeTableAliases,
    )

    if (isolateTables) {
        result = isolateTableSelectsRule(result, schema = resolvedSchema)
    }

    if (qualifyColumns) {
        result = qualifyColumnsRule(
            result,
            resolvedSchema,
            expandAliasRefs = expandAliasRefs,
            expandStars = expandStars,
            inferSchema = inferSchema,
            allowPartialQualification = allowPartialQualification,
        )
    }

    if (quoteIdentifiers) {
        result = quoteIdentifiersRule(result, dialect = resolvedDialect, identify = identify)
    }

    if (validateQualifyColumns) {
        validateQualifyColumnsRule(result, sql = sql)
    }

    return result
}
