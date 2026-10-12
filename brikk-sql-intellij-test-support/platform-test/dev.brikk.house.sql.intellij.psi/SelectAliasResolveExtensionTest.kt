package dev.brikk.house.sql.intellij.psi

import com.intellij.database.Dbms
import com.intellij.psi.PsiElement
import com.intellij.sql.psi.SqlWhereClause
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.intellij.completion.configureDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.reflect.KClass

class SelectAliasResolveExtensionTest : BasePlatformTestCase() {
    /** Completion items in a PostgreSQL file, whose platform rules hide SELECT aliases from HAVING and WHERE. */
    private fun offered(text: String, extension: SqlResolveExtension?): List<String> {
        extension?.let { SqlResolveExtension.EP_NAME.point.registerExtension(it, testRootDisposable) }
        myFixture.configureDialect("PostgreSQL", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun extension(dbms: Dbms, vararg clauses: KClass<out PsiElement>) =
        if (clauses.isEmpty()) object : SelectAliasResolveExtension(dbms) {} else object : SelectAliasResolveExtension(dbms, clauses.toSet()) {}

    private val having = "SELECT a AS watches, a AS wall FROM t GROUP BY a HAVING wa<caret>"

    fun testHavingSeesSelectAliasesOnlyWithTheExtension() {
        assertDoesntContain(offered(having, null), "watches")
        assertContainsElements(offered(having, extension(Dbms.POSTGRES)), "watches")
    }

    fun testAnotherDbmsIsLeftAlone() {
        assertDoesntContain(offered(having, extension(Dbms.MYSQL)), "watches")
    }

    fun testQualifiedReferencesDoNotSeeAliases() {
        assertDoesntContain(offered("SELECT a AS watches, a AS wall FROM t GROUP BY a HAVING t.wa<caret>", extension(Dbms.POSTGRES)), "watches")
    }

    fun testOnlyTheConfiguredClausesSeeAliases() {
        val where = "SELECT a AS watches, a AS wall FROM t WHERE wa<caret>"
        assertDoesntContain(offered(where, extension(Dbms.POSTGRES)), "watches")
        assertContainsElements(offered(where, extension(Dbms.POSTGRES, SqlWhereClause::class)), "watches")
    }
}
