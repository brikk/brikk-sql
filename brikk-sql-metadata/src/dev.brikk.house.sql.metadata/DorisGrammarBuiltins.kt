package dev.brikk.house.sql.metadata

import dev.brikk.house.sql.metadata.InvocationSyntax.BARE
import dev.brikk.house.sql.metadata.InvocationSyntax.CALL
import dev.brikk.house.sql.metadata.InvocationSyntax.SPECIAL_FORM

/*
 * HANDWRITTEN (not generated): function-shaped names Doris accepts at the parser/operator
 * level, so they're absent from the Nereids function registry that generates
 * GeneratedDorisFunctionCatalog.kt (verified absent from vendor/data/doris-signatures.json).
 * generate_doris_functions.py wires this set in via `grammarBuiltins = DORIS_GRAMMAR_BUILTINS`.
 *
 * Inclusion rule (as for TRINO_GRAMMAR_BUILTINS): callable in Doris, NOT in the registry
 * dump, and actually parsed by brikk-sql (each has an AST node/operator) so listing it
 * clears a false "unknown" rather than masking a real parse failure.
 */

/** Doris names parsed as grammar special forms / operators, absent from the function registry. */
val DORIS_GRAMMAR_BUILTINS: Set<String> = setOf(
    "TIMESTAMPADD",   // TIMESTAMPADD(unit, n, dt) — unit is a keyword; rewritten to the *_ADD family
    "TIMESTAMPDIFF",  // TIMESTAMPDIFF(unit, a, b) — unit is a keyword; rewritten to the *_DIFF family
    "MOD",            // modulo: operator `a MOD b` and MySQL-compat MOD(a, b) (FMOD/PMOD are registered)
    "SYSDATE",        // MySQL-compat current-datetime special form
    "EXTRACT",        // EXTRACT(unit FROM x) — unit keyword, standard-SQL grammar form
    "CAST",           // CAST(x AS type) — grammar form
    "CONVERT",        // CONVERT(x, type) / CONVERT(x USING charset) — MySQL-compat grammar form
    "DEFAULT",        // DEFAULT(column) reads a write-schema default, not the column's row value
)

/*
 * Grammar forms for completion (DF-13): every name above plus the registered names with
 * their own DorisParser.g4 (4.1.5) primaryExpression rule. CURRENT_DATE and the other
 * datetime/user names are non-reserved there, so `name()` parses as a call as well.
 */
internal val DORIS_GRAMMAR_FORMS: List<GrammarForm> = listOf(
    grammarForm("TIMESTAMPADD", SPECIAL_FORM, template = "TIMESTAMPADD(unit, interval, datetime)"),
    grammarForm("TIMESTAMPDIFF", SPECIAL_FORM, template = "TIMESTAMPDIFF(unit, start, end)"),
    grammarForm("MOD", CALL),
    grammarForm("SYSDATE", CALL),
    grammarForm("EXTRACT", SPECIAL_FORM, template = "EXTRACT(unit FROM expression)"),
    grammarForm("CAST", SPECIAL_FORM, template = "CAST(expression AS type)"),
    grammarForm("CONVERT", SPECIAL_FORM, template = "CONVERT(expression, type) | CONVERT(expression USING charset)"),
    grammarForm("DEFAULT", CALL),
    grammarForm("TRY_CAST", SPECIAL_FORM, template = "TRY_CAST(expression AS type)"),
    grammarForm("CURRENT_DATE", BARE, CALL),
    grammarForm("CURRENT_TIME", BARE, CALL),
    grammarForm("CURRENT_TIMESTAMP", BARE, CALL),
    grammarForm("LOCALTIME", BARE, CALL),
    grammarForm("LOCALTIMESTAMP", BARE, CALL),
    grammarForm("CURRENT_USER", BARE, CALL),
    grammarForm("SESSION_USER", BARE, CALL),
    grammarForm("TRIM", CALL, SPECIAL_FORM, template = "TRIM([BOTH | LEADING | TRAILING] [characters] FROM string)"),
    grammarForm("SUBSTRING", CALL, SPECIAL_FORM, template = "SUBSTRING(string FROM start [FOR length])"),
    grammarForm("POSITION", CALL, SPECIAL_FORM, template = "POSITION(substring IN string)"),
)
