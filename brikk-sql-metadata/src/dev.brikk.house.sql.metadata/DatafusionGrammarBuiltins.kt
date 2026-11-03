package dev.brikk.house.sql.metadata

/** Parser special forms, separate from the captured function registry. */
internal val DATAFUSION_GRAMMAR_FORMS: List<GrammarForm> = listOf(
    grammarForm("CAST", InvocationSyntax.SPECIAL_FORM, template = "CAST(expression AS type)"),
    grammarForm("TRY_CAST", InvocationSyntax.SPECIAL_FORM, template = "TRY_CAST(expression AS type)"),
    grammarForm("EXTRACT", InvocationSyntax.SPECIAL_FORM, template = "EXTRACT(field FROM expression)"),
)

internal val DATAFUSION_GRAMMAR_BUILTINS: Set<String> = setOf("CAST", "TRY_CAST", "EXTRACT")
