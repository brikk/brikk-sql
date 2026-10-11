package dev.brikk.house.sql.intellij

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder

/** Shared column presentation. Consumers supply semantic facts; no Kotlin IDE types are required. */
public object SqlColumnCompletion {
    private data class Identity(val name: String, val alias: String)

    /** [nullable] is true/false when known and null when the contract cannot prove it. */
    public fun create(
        name: String,
        typeName: String?,
        nullable: Boolean?,
        relationAlias: String? = null,
        originLabel: String = "brikk column",
        priority: Double = 120.0,
    ): LookupElement {
        val alias = relationAlias?.takeIf { it.isNotBlank() }.orEmpty()
        val nullability = when (nullable) {
            true -> "?"
            false -> ""
            null -> " (nullability unknown)"
        }
        val origin = "  $originLabel" + if (alias.isEmpty()) "" else " ($alias)"
        // Different relations may supply the same column name. Preserve both lookup identities.
        val element = LookupElementBuilder.create(Identity(name, alias), name)
            .withTypeText((typeName ?: "unknown type") + nullability, true)
            .withTailText(origin, true)
        return PrioritizedLookupElement.withPriority(element, priority)
    }
}
