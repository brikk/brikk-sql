package dev.brikk.house.sql.intellij.catalog

import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.sql.dialects.postgres.PgDialect
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

class QualifiedPathIntrospectContributorTest : AutoIntrospectTestCase() {
    class Contributor : QualifiedPathIntrospectContributor(Service::class.java)

    override fun setUp() {
        super.setUp()
        val plugin = DefaultPluginDescriptor(PluginId.getId("dev.brikk.house.sql.intellij.test"), Contributor::class.java.classLoader)
        CompletionContributor.EP.point.registerExtension(CompletionContributorEP(PgDialect.INSTANCE.id, Contributor::class.java.name, plugin), LoadingOrder.FIRST, testRootDisposable)
    }

    fun testCompletionAfterACatalogIntrospectsIt() {
        complete("SELECT * FROM hive.<caret>")
        assertEquals("hive", pending())
        nextRefresh().finish { hive.schemas.createOrGet("web") }
        awaitIdle()
    }

    fun testQuotedNamesAndTheNameBeingTyped() {
        complete("SELECT * FROM \"hi\"\"ve\".x, \"memory\" . we<caret>")
        assertNull("not a path: a space before the dot", pending())
        complete("SELECT * FROM \"memory\".we<caret>")
        assertEquals("memory", pending())
        nextRefresh().finish { memory.schemas.createOrGet("web") }
        awaitIdle()
        complete("SELECT * FROM \"q\"\"t\".<caret>")
        assertEquals("q\"t", pending())
        nextRefresh().finish()
        awaitIdle()
    }

    fun testOnlyThePathRightBeforeTheCaretCounts() {
        complete("SELECT hive.x FROM <caret>")
        complete("SELECT 1.5<caret>")
        complete("SELECT memory<caret>")
        assertNoRefresh()
        assertNull(pending())
        complete("SELECT memory.<caret>")
        assertEquals("memory", pending())
        nextRefresh().finish()
        awaitIdle()
    }

    /** A file attached to a console of another dbms never introspects the project's PostgreSQL source. */
    fun testAConsoleOfAnotherDbmsIsNotIntrospected() {
        configure("SELECT * FROM hive.<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "h2.unified").use {
            completeHere()
            assertNoRefresh()
        }
        completeHere()
        assertEquals("without the console, the project's PostgreSQL source serves the file", "hive", pending())
        nextRefresh().finish()
        awaitIdle()
    }

    fun testOffUntilTheDialectEnablesIt() {
        useService(Profile(autoIntrospect = false))
        complete("SELECT * FROM hive.<caret>")
        assertNoRefresh()
        useService(Profile())
        completeHere()
        assertEquals("hive", pending())
        nextRefresh().finish()
        awaitIdle()
    }

    private fun configure(text: String) {
        myFixture.configureByText("path-${UUID.randomUUID()}.sql", text)
        setSqlDialectMapping(project, myFixture.file.virtualFile, PgDialect.INSTANCE, testRootDisposable)
    }

    private fun complete(text: String) {
        configure(text)
        completeHere()
    }

    private fun completeHere() {
        LookupManager.getInstance(project).hideActiveLookup()
        myFixture.completeBasic()
        LookupManager.getInstance(project).hideActiveLookup()
    }
}
