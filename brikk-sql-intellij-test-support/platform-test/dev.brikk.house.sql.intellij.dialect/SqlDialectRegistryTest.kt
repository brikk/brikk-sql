package dev.brikk.house.sql.intellij.dialect

import com.intellij.database.Dbms
import com.intellij.lang.Language
import com.intellij.psi.PsiManager
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import java.util.UUID

class SqlDialectRegistryTest : BasePlatformTestCase() {
    fun testEveryEntryNamesACoreDialect() {
        for (entry in SqlDialectRegistry.entries) assertNotNull(entry.coreDialect, Dialects.forNameOrNull(entry.coreDialect))
    }

    fun testIdentitiesAndMarkersAreUnique() {
        val entries = SqlDialectRegistry.entries
        for (key in listOf<(SqlDialectEntry) -> Collection<String>>({ it.languageIds }, { it.dbmsIds }, { listOf(it.marker.lowercase()) }, { listOf(it.coreDialect) })) {
            val all = entries.flatMap(key)
            assertEquals(all.toSet().size, all.size)
        }
    }

    /** A plugin's language is absent here; a language the IDE has must be of a listed dbms. */
    fun testRegisteredLanguagesAgreeWithTheirDbms() {
        for (entry in SqlDialectRegistry.entries) for (id in entry.languageIds) {
            val language = Language.findLanguageByID(id) as? SqlLanguageDialect ?: continue
            assertTrue("$id is ${language.dbms.name}", language.dbms.name in entry.dbmsIds)
            assertSame(entry, SqlDialectRegistry.forLanguage(language))
            assertSame(entry, SqlDialectRegistry.forDbms(language.dbms))
        }
    }

    fun testLookups() {
        assertEquals("mysql", Language.findLanguageByID("MariaDB")?.let(SqlDialectRegistry::forLanguage)?.coreDialect)
        assertEquals("postgres", SqlDialectRegistry.forDbms(Dbms.POSTGRES)?.coreDialect)
        assertEquals("Spark", SqlDialectRegistry.forMarker("SPARK2")?.label)
        assertEquals("trino", SqlDialectRegistry.forLanguageId("TrinoSQL")?.coreDialect)
        assertNull(Language.findLanguageByID("Oracle")?.let(SqlDialectRegistry::forLanguage))
    }

    fun testMappingSetterAppliesAndClearsTheDialect() {
        val file = myFixture.configureByText("mapped-${UUID.randomUUID()}.sql", "SELECT 1").virtualFile
        setSqlDialectMapping(project, file, PgDialect.INSTANCE, testRootDisposable)
        assertEquals(PgDialect.INSTANCE, PsiManager.getInstance(project).findFile(file)?.language)
        assertTrue(setDialectMapping(project, file, null))
        assertNotSame(PgDialect.INSTANCE, PsiManager.getInstance(project).findFile(file)?.language)
    }
}
