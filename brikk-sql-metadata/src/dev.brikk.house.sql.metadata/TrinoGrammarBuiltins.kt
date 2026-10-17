package dev.brikk.house.sql.metadata

import dev.brikk.house.sql.metadata.InvocationSyntax.BARE
import dev.brikk.house.sql.metadata.InvocationSyntax.CALL
import dev.brikk.house.sql.metadata.InvocationSyntax.SPECIAL_FORM

/*
 * HANDWRITTEN (not generated): engine-GRAMMAR knowledge for Trino, curated by reading
 * the pinned Trino 483 sources — it cannot be derived from the `SHOW FUNCTIONS` dump
 * that generates GeneratedTrinoFunctionCatalog.kt, precisely because these names are
 * absent from the registry. tools/generate_trino_functions.py wires this set into the
 * generated FunctionCatalog constructor (grammarBuiltins = TRINO_GRAMMAR_BUILTINS).
 *
 * NOTE (483 grammar, not yet modeled): SqlBase.g4 also has the function-shaped
 * `NEAREST '(' ... MATCH booleanExpression ')'` (#nearest) relation primary. It is
 * intentionally NOT listed here yet — the parser does not handle the `NEAREST` join
 * clause at all, so listing it would mask a real parse failure rather than clear a
 * false positive. Add it together with parser support (see the 476–483 grammar gaps).
 *
 * Sources (verify on refresh, both in reference/trino at the 483 pin):
 *  - core/trino-grammar/src/main/antlr4/io/trino/grammar/sql/SqlBase.g4 — dedicated
 *    grammar alternatives (rule labels cited per name below).
 *  - core/trino-parser/src/main/java/io/trino/sql/parser/AstBuilder.java
 *    visitFunctionCall — parser special forms: they parse through the generic
 *    #functionCall alternative but are lifted into dedicated AST nodes by name before
 *    analysis, and are never registered in the function registry.
 *
 * Inclusion rule: function-shaped names an SQL text can legitimately call in Trino
 * that `SHOW FUNCTIONS` does NOT list. Names whose grammar rule exists but that ARE
 * registered at 481 (TRIM, SUBSTRING, NORMALIZE, LISTAGG, CURRENT_DATE, MAP) are
 * deliberately excluded — the registry catalog already clears them. ARRAY is excluded
 * too: its constructor is bracket syntax (`ARRAY[...]`, #arrayConstructor), so a
 * function-shaped `ARRAY(...)` call would NOT parse — listing it would mask a real
 * failure.
 */

/** Trino names parsed at the grammar/parser level, absent from `SHOW FUNCTIONS`. */
val TRINO_GRAMMAR_BUILTINS: Set<String> = setOf(
    // SqlBase.g4 primaryExpression alternatives:
    "POSITION",             // #position: POSITION '(' valueExpression IN valueExpression ')'
    "ROW",                  // #rowConstructor: ROW '(' fieldConstructor (',' ...)* ')'
    "EXISTS",               // #exists: EXISTS '(' query ')'
    "UNIQUE",               // #unique: UNIQUE '(' query ')'
    "CAST",                 // #cast: CAST '(' expression AS type ')'
    "TRY_CAST",             // #cast: TRY_CAST '(' expression AS type ')'
    "OVERLAY",              // #overlay: OVERLAY '(' ... PLACING ... FROM ... ')'
    "EXTRACT",              // #extract: EXTRACT '(' identifier FROM valueExpression ')'
    "GROUPING",             // #groupingOperation: GROUPING '(' (qualifiedName ...)? ')'
    "JSON_EXISTS",          // #jsonExists
    "JSON_VALUE",           // #jsonValue
    "JSON_QUERY",           // #jsonQuery
    "JSON_OBJECT",          // #jsonObject
    "JSON_ARRAY",           // #jsonArray
    // SqlBase.g4 primaryExpression niladic/precision forms (CURRENT_DATE itself IS
    // registered at 481, so it lives in the generated catalog, not here):
    "CURRENT_TIME",         // #currentTime
    "CURRENT_TIMESTAMP",    // #currentTimestamp
    "LOCALTIME",            // #localTime
    "LOCALTIMESTAMP",       // #localTimestamp
    "CURRENT_USER",         // #currentUser
    "CURRENT_CATALOG",      // #currentCatalog
    "CURRENT_SCHEMA",       // #currentSchema
    "CURRENT_PATH",         // #currentPath
    // SqlBase.g4 relationPrimary alternatives (function-shaped in table position):
    "UNNEST",               // #unnest: UNNEST '(' expression (',' expression)* ')'
    "JSON_TABLE",           // #jsonTable: JSON_TABLE '(' jsonPathInvocation COLUMNS ... ')'
    // AstBuilder.visitFunctionCall parser special forms (dedicated AST nodes by name):
    "IF",                   // -> IfExpression
    "NULLIF",               // -> NullIfExpression
    "COALESCE",             // -> CoalesceExpression
    "TRY",                  // -> TryExpression
    "FORMAT",               // -> Format
)

/*
 * Grammar forms for completion (DF-13): every name above plus the registered names whose
 * SqlBase.g4 rule changes how they are written (reserved CURRENT_DATE is bare only; TRIM,
 * SUBSTRING, NORMALIZE take keyword arguments; LISTAGG needs WITHIN GROUP). From the 483
 * grammar rule cited for each name above.
 */
internal val TRINO_GRAMMAR_FORMS: List<GrammarForm> = listOf(
    grammarForm("POSITION", SPECIAL_FORM, template = "POSITION(substring IN string)"),
    grammarForm("ROW", CALL),
    grammarForm("EXISTS", SPECIAL_FORM, template = "EXISTS(query)"),
    grammarForm("UNIQUE", SPECIAL_FORM, template = "UNIQUE(query)"),
    grammarForm("CAST", SPECIAL_FORM, template = "CAST(expression AS type)"),
    grammarForm("TRY_CAST", SPECIAL_FORM, template = "TRY_CAST(expression AS type)"),
    grammarForm("OVERLAY", SPECIAL_FORM, template = "OVERLAY(string PLACING replacement FROM start [FOR length])"),
    grammarForm("EXTRACT", SPECIAL_FORM, template = "EXTRACT(field FROM expression)"),
    grammarForm("GROUPING", CALL),
    grammarForm("JSON_EXISTS", SPECIAL_FORM, template = "JSON_EXISTS(json, path)"),
    grammarForm("JSON_VALUE", SPECIAL_FORM, template = "JSON_VALUE(json, path)"),
    grammarForm("JSON_QUERY", SPECIAL_FORM, template = "JSON_QUERY(json, path)"),
    grammarForm("JSON_OBJECT", SPECIAL_FORM, template = "JSON_OBJECT(key VALUE value, ...)"),
    grammarForm("JSON_ARRAY", SPECIAL_FORM, template = "JSON_ARRAY(value, ...)"),
    // Optional precision: CURRENT_TIMESTAMP or CURRENT_TIMESTAMP(3).
    grammarForm("CURRENT_TIME", BARE, CALL),
    grammarForm("CURRENT_TIMESTAMP", BARE, CALL),
    grammarForm("LOCALTIME", BARE, CALL),
    grammarForm("LOCALTIMESTAMP", BARE, CALL),
    grammarForm("CURRENT_USER", BARE),
    grammarForm("CURRENT_CATALOG", BARE),
    grammarForm("CURRENT_SCHEMA", BARE),
    grammarForm("CURRENT_PATH", BARE),
    grammarForm("UNNEST", CALL, kind = FunctionKind.TABLE_VALUED),
    grammarForm("JSON_TABLE", SPECIAL_FORM, kind = FunctionKind.TABLE_VALUED, template = "JSON_TABLE(json, path COLUMNS (...))"),
    grammarForm("IF", CALL),
    grammarForm("NULLIF", CALL),
    grammarForm("COALESCE", CALL),
    grammarForm("TRY", CALL),
    grammarForm("FORMAT", CALL),
    // Registered functions with their own grammar rule.
    grammarForm("CURRENT_DATE", BARE),
    grammarForm("TRIM", CALL, SPECIAL_FORM, template = "TRIM([BOTH | LEADING | TRAILING] [characters] FROM string)"),
    grammarForm("SUBSTRING", CALL, SPECIAL_FORM, template = "SUBSTRING(string FROM start [FOR length])"),
    grammarForm("NORMALIZE", CALL, SPECIAL_FORM, template = "NORMALIZE(string [, NFC | NFD | NFKC | NFKD])"),
    grammarForm("LISTAGG", SPECIAL_FORM, kind = FunctionKind.AGGREGATE, template = "LISTAGG(expression, separator) WITHIN GROUP (ORDER BY ...)"),
)
