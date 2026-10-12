package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.project.DumbAware
import com.intellij.sql.dialects.BuiltinFunction
import org.jetbrains.annotations.ApiStatus

/**
 * Drops the base dialect's builtin-function items that [SqlCompletionProfile.builtinFunctionFilter]
 * does not keep. Only items whose object is a platform `BuiltinFunction.Prototype` can be dropped,
 * so a column or table that shares a builtin's name stays. It also runs while indexes are built,
 * when the platform still offers its builtins. Register it with `order="first"`
 * through a no-arg subclass.
 */
@ApiStatus.Experimental
public abstract class SqlBuiltinPrototypeFilter(private val profile: SqlCompletionProfile) : CompletionContributor(), DumbAware {
    final override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.language.isKindOf(profile.language)) return
        val keeps = profile.builtinFunctionFilter(file) ?: return
        result.runRemainingContributors(parameters) { completion ->
            val item = completion.lookupElement
            if (item.`object` !is BuiltinFunction.Prototype || keeps(item.lookupString)) result.passResult(completion)
        }
    }
}
