package dev.brikk.house.sql.intellij.relation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.Language
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.Disposable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts

/** A provider whose facts, revision, availability and navigation targets each test sets. */
class FakeFacts(
    var facts: RelationFacts = EVENTS,
    var revision: Long = 0,
    var pending: Boolean = false,
    override var platformResolves: Boolean = false,
    override var labels: RelationColumnLabels? = null,
) : ExternalRelationFacts {
    var targets: (PsiFile, String) -> PsiElement? = { _, _ -> null }
    override fun facts(file: PsiFile): RelationFactsResult =
        if (pending) RelationFactsResult.Pending("waiting") else RelationFactsResult.Available(facts, revision)
    override fun navigationTarget(file: PsiFile, provenance: String) = targets(file, provenance)

    companion object {
        val EVENTS = RelationFacts(slots = mapOf(
            "events" to RelationFact(listOf(
                ColumnFact("id", displayType = "Long", nullability = Nullability.NON_NULL, provenance = "decl:id"),
                ColumnFact("kind", displayType = "Kind", nullability = Nullability.NULLABLE, provenance = "decl:kind"),
            ), provenance = "decl:events"),
            "partial" to RelationFact(listOf(ColumnFact("id", provenance = "decl:id")), Knowledge.PARTIAL),
        ))
    }
}

class TestRelationProfile(override val language: Language, override val dialect: String) : RelationScopeProfile {
    override var providers: List<ExternalRelationFacts> = listOf(FakeFacts())
    override var labels = RelationColumnLabels()
    val fake: FakeFacts get() = providers.first() as FakeFacts
    fun reset() {
        providers = listOf(FakeFacts())
        labels = RelationColumnLabels()
    }
}

val postgres: Language get() = Language.findLanguageByID("PostgreSQL")!!
val mysql: Language get() = Language.findLanguageByID("MySQL")!!

val pgRelations = TestRelationProfile(postgres, "postgres")
val mysqlRelations = TestRelationProfile(mysql, "mysql")

class PgRelationResolve : ExternalRelationResolveExtension(pgRelations)
class PgRelationSuppressor : ExternalRelationInspectionSuppressor(pgRelations)
class PgRelationGoto : ExternalRelationGotoDeclarationHandler(pgRelations)
class PgRelationAnnotator : ExternalRelationAnnotator(pgRelations)
class MysqlRelationAnnotator : ExternalRelationAnnotator(mysqlRelations)

/** Registers every feature of [pgRelations] until [until] ends. */
fun registerPgRelations(until: Disposable, resolve: Boolean = true, suppressor: Boolean = true) {
    if (resolve) SqlResolveExtension.EP_NAME.point.registerExtension(PgRelationResolve(), until)
    if (suppressor) SqlInspectionSuppressorDelegate.EP_NAME.point.registerExtension(PgRelationSuppressor(), until)
    GotoDeclarationHandler.EP_NAME.point.registerExtension(PgRelationGoto(), until)
    LanguageAnnotators.INSTANCE.addExplicitExtension(postgres, PgRelationAnnotator(), until)
}
