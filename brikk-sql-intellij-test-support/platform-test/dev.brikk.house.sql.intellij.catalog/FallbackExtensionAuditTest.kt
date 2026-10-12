package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.model.ModelFacade
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertFallbackExtensionsAudited
import dev.brikk.house.sql.intellij.testing.inheritedDbmsExtensions

class FallbackExtensionAuditTest : BasePlatformTestCase() {
    class Gate : GenericJdbcIntrospectorGate()
    class Facade(dbms: Dbms) : PgFamilyModelFacade(dbms)

    private val dbms = CatalogTestDbms.AUDITED
    private val base = CatalogTestDbms.AUDITED_BASE

    override fun setUp() {
        super.setUp()
        registerDbms(dbms, testRootDisposable)
        registerDbms(base, testRootDisposable)
    }

    /** The leak the audit exists for: a PostgreSQL fallback hands over its dialect and introspector unasked. */
    fun testAnUnauditedFallbackFails() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        val inherited = inheritedDbmsExtensions(dbms)
        assertEquals("POSTGRES", inherited["com.intellij.database.dialect"])
        assertEquals("POSTGRES", inherited["com.intellij.database.introspector"])
        val failure = failureOf(emptyMap())
        assertTrue(failure, "com.intellij.database.introspector is inherited from POSTGRES" in failure)
    }

    fun testOverridesAndReasonsPass() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("introspector", dbms, Gate::class.java, testRootDisposable)
        val inherited = inheritedDbmsExtensions(dbms)
        assertFalse("an override is not inherited", "com.intellij.database.introspector" in inherited)
        assertFallbackExtensionsAudited(dbms, inherited.mapValues { "PostgreSQL behavior is right here" })
    }

    fun testBlankReasonsAndStaleEntriesFail() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("introspector", dbms, Gate::class.java, testRootDisposable)
        val reasons = inheritedDbmsExtensions(dbms).mapValues { "PostgreSQL behavior is right here" }
        val stale = failureOf(reasons + ("com.intellij.database.introspector" to "overridden since"))
        assertTrue(stale, "com.intellij.database.introspector is allowed but not inherited" in stale)
        val blank = failureOf(reasons + ("com.intellij.database.dialect" to " "))
        assertTrue(blank, "com.intellij.database.dialect has a blank reason" in blank)
    }

    /** Inheritance follows the whole chain and stops at the first dbms with its own extension, as the platform resolves it. */
    fun testTheChainIsFollowed() {
        registerFallback(dbms, base, testRootDisposable)
        registerFallback(base, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("modelFacade", base, Facade::class.java, testRootDisposable)
        val inherited = inheritedDbmsExtensions(dbms)
        assertEquals(base.name, inherited["com.intellij.database.modelFacade"])
        assertEquals("POSTGRES", inherited["com.intellij.database.dialect"])
        assertInstanceOf(ModelFacade.forDbms(dbms), Facade::class.java)
        assertTrue(inheritedDbmsExtensions(Dbms.POSTGRES).isEmpty())
    }

    private fun failureOf(allowed: Map<String, String>): String {
        val failure = runCatching { assertFallbackExtensionsAudited(dbms, allowed) }.exceptionOrNull()
        assertInstanceOf(failure, AssertionError::class.java)
        return failure?.message.orEmpty()
    }
}
