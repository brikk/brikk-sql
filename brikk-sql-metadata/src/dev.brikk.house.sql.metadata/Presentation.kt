package dev.brikk.house.sql.metadata

import kotlinx.serialization.Serializable

/** How a name is written at a call site; an engine may accept several for one name. */
enum class InvocationSyntax {
    /** `name(arguments)`. */
    CALL,

    /** `name` without parentheses (`CURRENT_TIMESTAMP`). */
    BARE,

    /** Parentheses holding keywords as well as expressions (`CAST(x AS t)`, `EXTRACT(f FROM x)`). */
    SPECIAL_FORM,
}

/**
 * How the engine's grammar accepts a function-shaped [name], registered or not. [syntaxes]
 * are every form the parser accepts; [template] shows a special form (`CAST(expression AS
 * type)`). A form for a registered function overrides its default `name(arguments)`.
 */
@Serializable
data class GrammarForm(
    val name: String,
    val syntaxes: Set<InvocationSyntax>,
    val kind: FunctionKind = FunctionKind.SCALAR,
    val template: String? = null,
)

internal fun grammarForm(
    name: String,
    vararg syntaxes: InvocationSyntax,
    kind: FunctionKind = FunctionKind.SCALAR,
    template: String? = null,
) = GrammarForm(name, syntaxes.toSet(), kind, template)

/**
 * One completion entry: a function name (or alias) under one kind. [signature] and
 * [returnType] come from the first overload (`signature` lists argument types, `...` for
 * variadic), null without overloads. [extension] is set for functions an extension adds.
 */
data class FunctionPresentation(
    val name: String,
    val isAlias: Boolean,
    val kind: FunctionKind,
    val nativeKind: String?,
    val signature: String?,
    val returnType: String?,
    val overloadCount: Int,
    val extension: String?,
    val syntaxes: Set<InvocationSyntax>,
    val template: String? = null,
)

/** This def presented under [asName] (its name or one of its aliases), called as `name(...)`. */
fun FunctionDef.presentation(asName: String = name): FunctionPresentation {
    val first = overloads.firstOrNull()
    return FunctionPresentation(
        name = asName,
        isAlias = asName != name,
        kind = kind,
        nativeKind = nativeKind,
        signature = first?.run { (argTypes + if (variadic) listOf("...") else emptyList()).joinToString(", ") },
        returnType = first?.returnType,
        overloadCount = overloads.size,
        extension = extension,
        syntaxes = setOf(InvocationSyntax.CALL),
    )
}

private fun GrammarForm.presentation() = FunctionPresentation(
    name = name,
    isAlias = false,
    kind = kind,
    nativeKind = null,
    signature = null,
    returnType = null,
    overloadCount = 0,
    extension = null,
    syntaxes = syntaxes,
    template = template,
)

/**
 * One entry per (name or alias, kind) across the registered functions, the extension
 * functions and the grammar forms, so a name defined as both scalar and table function
 * yields two entries. Names are compared by the dialect's case policy
 * ([FunctionCatalog.caseSensitive]); the base registry wins over an extension, and a grammar
 * form supplies the invocation syntaxes of the registered entry it matches. An entry that
 * only a grammar form provides keeps the form's spelling (uppercase, `CURRENT_TIMESTAMP`);
 * registered entries keep the registry's spelling.
 */
fun MetadataSnapshot.completionEntries(): List<FunctionPresentation> {
    fun key(name: String, kind: FunctionKind) = functions.nameKey(name) to kind
    val forms = grammarForms.orEmpty().associateBy { key(it.name, it.kind) }
    val entries = LinkedHashMap<Pair<String, FunctionKind>, FunctionPresentation>()
    for (def in functions.functions + extensionFunctions.orEmpty()) {
        for (name in listOf(def.name) + def.aliases) {
            val key = key(name, def.kind)
            entries.getOrPut(key) {
                val form = forms[key]
                def.presentation(name).let { if (form == null) it else it.copy(syntaxes = form.syntaxes, template = form.template) }
            }
        }
    }
    for (form in grammarForms.orEmpty()) entries.getOrPut(key(form.name, form.kind)) { form.presentation() }
    return entries.values.toList()
}
