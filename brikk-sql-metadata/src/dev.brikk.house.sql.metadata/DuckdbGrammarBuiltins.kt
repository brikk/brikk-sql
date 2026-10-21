package dev.brikk.house.sql.metadata

import dev.brikk.house.sql.metadata.InvocationSyntax.BARE
import dev.brikk.house.sql.metadata.InvocationSyntax.CALL
import dev.brikk.house.sql.metadata.InvocationSyntax.SPECIAL_FORM

/*
 * HANDWRITTEN (not generated): engine-GRAMMAR knowledge for DuckDB — names the
 * (Postgres-derived) parser accepts as function-shaped calls that duckdb_functions()
 * does NOT list (they become dedicated expression nodes at parse time, so they never
 * enter the registry captured in vendor/data/duckdb-functions-<version>.tsv).
 * DuckdbDump.toSnapshot() wires this set into every DuckDB FunctionCatalog
 * constructor (grammarBuiltins = DUCKDB_GRAMMAR_BUILTINS).
 *
 * Verified against the pinned engine (v2.0.0): each name below
 * parses as `NAME(args)` (grammar-accepted; GROUPING/GROUPING_ID fail only at BIND
 * time outside a grouped query) while `SELECT DISTINCT function_name FROM
 * duckdb_functions()` lacks it.
 *
 * Inclusion rule (deliberately minimal): only names brikk-sql generators can actually
 * fallback-emit. Grammar-level names that every generator renders through a dedicated
 * renderer (IF, TRY, CAST, TRY_CAST, EXTRACT) never reach the capability check and are
 * excluded; IFNULL is excluded because every dialect parses it to the Coalesce node,
 * which renders as COALESCE.
 */

/** DuckDB names parsed at the grammar level, absent from duckdb_functions(). */
val DUCKDB_GRAMMAR_BUILTINS: Set<String> = setOf(
    "COALESCE",     // parser special form (dedicated expression, not a registry call)
    "GROUPING",     // grammar-level grouping operation (binder-restricted to grouped queries)
    "GROUPING_ID",  // alias form of the grouping operation, same grammar path
)

/*
 * Grammar forms for completion (DF-13): every name above, plus the grammar-level names its
 * inclusion rule leaves out (IF, TRY, CAST, TRY_CAST, EXTRACT), the bare spellings the parser
 * accepts, and the special argument syntax of registered functions. Each probed on the
 * 2.0.0 JDBC engine (bare and call spellings both tried).
 */
internal val DUCKDB_GRAMMAR_FORMS: List<GrammarForm> = listOf(
    grammarForm("COALESCE", CALL),
    grammarForm("GROUPING", CALL),
    grammarForm("GROUPING_ID", CALL),
    grammarForm("IF", CALL),
    grammarForm("TRY", CALL),
    grammarForm("CAST", SPECIAL_FORM, template = "CAST(expression AS type)"),
    grammarForm("TRY_CAST", SPECIAL_FORM, template = "TRY_CAST(expression AS type)"),
    grammarForm("EXTRACT", SPECIAL_FORM, template = "EXTRACT(field FROM expression)"),
    grammarForm("POSITION", CALL, SPECIAL_FORM, template = "POSITION(substring IN string)"),
    grammarForm("TRIM", CALL, SPECIAL_FORM, template = "TRIM([BOTH | LEADING | TRAILING] [characters] FROM string)"),
    grammarForm("SUBSTRING", CALL, SPECIAL_FORM, template = "SUBSTRING(string FROM start [FOR length])"),
    // `current_timestamp()` and friends are rejected: bare only.
    grammarForm("CURRENT_TIMESTAMP", BARE),
    grammarForm("CURRENT_TIME", BARE),
    grammarForm("LOCALTIME", BARE),
    grammarForm("LOCALTIMESTAMP", BARE),
    // Registered functions the parser also accepts bare.
    grammarForm("CURRENT_DATE", BARE, CALL),
    grammarForm("CURRENT_CATALOG", BARE, CALL),
    grammarForm("CURRENT_SCHEMA", BARE, CALL),
    grammarForm("CURRENT_USER", BARE, CALL),
    grammarForm("CURRENT_ROLE", BARE, CALL),
    grammarForm("SESSION_USER", BARE, CALL),
    grammarForm("USER", BARE, CALL),
)
