package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.database.model.ModelFacade
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.Disposer
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
    }

    /** Without an explicit fallback, a dbms still inherits the generic dbms's extensions, as PostgreSQL itself does. */
    fun testEveryDbmsFallsBackToTheGenericDbms() {
        val inherited = inheritedDbmsExtensions(dbms)
        assertFalse(inherited.isEmpty())
        assertEquals(setOf(Dbms.UNKNOWN.name), inherited.values.toSet())
        assertTrue("com.intellij.database.introspector" in inherited)
        assertEquals(setOf(Dbms.UNKNOWN.name), inheritedDbmsExtensions(Dbms.POSTGRES).values.toSet())
        assertTrue(inheritedDbmsExtensions(Dbms.UNKNOWN).isEmpty())
        assertTrue(failureOf(emptyMap()).contains("is inherited from UNKNOWN"))
    }

    fun testAnUnloadablePointIsSkipped() {
        val area = ApplicationManager.getApplication().extensionArea
        val name = "dev.brikk.house.sql.intellij.test.unloadable"
        area.registerExtensionPoint(name, "dev.brikk.house.sql.intellij.test.MissingBean", ExtensionPoint.Kind.BEAN_CLASS, true)
        Disposer.register(testRootDisposable) { area.unregisterExtensionPoint(name) }
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        assertEquals("POSTGRES", inheritedDbmsExtensions(dbms)["com.intellij.database.dialect"])
    }

    fun testTheFirstFallbackWins() {
        registerFallback(dbms, Dbms.MYSQL, testRootDisposable)
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        assertEquals("MYSQL", inheritedDbmsExtensions(dbms)["com.intellij.database.dialect"])
    }

    /** Each inherited point resolves, through the platform's own lookup, to exactly the claimed dbms's extensions. */
    fun testInheritanceMatchesThePlatformLookup() {
        registerFallback(dbms, base, testRootDisposable)
        registerFallback(base, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("modelFacade", base, Facade::class.java, testRootDisposable)
        val area = ApplicationManager.getApplication().extensionArea
        val checked = inheritedDbmsExtensions(dbms).filter { (point, from) ->
            val claimed = area.getExtensionPoint<DbmsExtension.Bean<Any>>(point).extensionList.filter { it.key == from }.map { it.implementationClass }
            // Some platform extensions cannot be constructed for a test dbms; those are not compared.
            val actual = runCatching { DbmsExtension<Any>(point).allForDbms(dbms).map { it.javaClass.name } }.getOrNull() ?: return@filter false
            assertEquals(point, claimed, actual)
            true
        }
        assertTrue("compared ${checked.keys}", checked.keys.containsAll(listOf("com.intellij.database.modelFacade", "com.intellij.database.introspector", "com.intellij.database.dialect")))
        assertTrue(checked.values.toSet().containsAll(listOf(base.name, "POSTGRES", Dbms.UNKNOWN.name)))
    }

    private fun failureOf(allowed: Map<String, String>): String {
        val failure = runCatching { assertFallbackExtensionsAudited(dbms, allowed) }.exceptionOrNull()
        assertInstanceOf(failure, AssertionError::class.java)
        return failure?.message.orEmpty()
    }
}
