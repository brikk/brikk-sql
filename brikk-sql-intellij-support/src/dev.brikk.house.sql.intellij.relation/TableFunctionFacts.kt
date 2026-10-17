package dev.brikk.house.sql.intellij.relation

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.OutputSchema
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import org.jetbrains.annotations.ApiStatus

/**
 * The table functions whose columns metadata knows whatever their arguments ([OutputSchema.Fixed], such as
 * ClickHouse's `numbers` and its `number UInt64`), as call facts from the metadata [snapshot] of a file. A
 * function whose columns depend on its arguments or on external data is left to the analysis, open unless
 * its arguments declare them.
 */
@ApiStatus.Experimental
public class TableFunctionFacts(private val snapshot: (PsiFile) -> MetadataSnapshot?) : ExternalRelationFacts {
    @Volatile
    private var last: Pair<MetadataSnapshot, RelationFacts>? = null

    override fun facts(file: PsiFile): RelationFactsResult? {
        val metadata = snapshot(file) ?: return null
        val facts = last?.takeIf { it.first === metadata }?.second ?: factsOf(metadata).also { last = metadata to it }
        return RelationFactsResult.Available(facts, System.identityHashCode(metadata).toLong())
    }

    private fun factsOf(metadata: MetadataSnapshot): RelationFacts = RelationFacts(
        calls = metadata.tableFunctions.orEmpty().filter { it.queryable }.mapNotNull { function ->
            val schema = function.schema as? OutputSchema.Fixed ?: return@mapNotNull null
            function.name to RelationFact(schema.columns.map { ColumnFact(it.name, sqlType = it.type) })
        }.toMap(),
    )
}
