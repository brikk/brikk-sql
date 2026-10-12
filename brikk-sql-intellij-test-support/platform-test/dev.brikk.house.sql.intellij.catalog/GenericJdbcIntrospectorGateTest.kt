package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.introspection.supportedByNativeIntrospector
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertIntrospectorRoutesToGeneric

class GenericJdbcIntrospectorGateTest : BasePlatformTestCase() {
    class Gate : GenericJdbcIntrospectorGate()

    private val dbms = CatalogTestDbms.GATED

    override fun setUp() {
        super.setUp()
        registerDbms(dbms, testRootDisposable)
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
    }

    /** What the gate prevents: the PostgreSQL factory, inherited, accepts a Trino server's version. */
    fun testTheFallbackAloneIntrospectsNatively() {
        assertTrue(supportedByNativeIntrospector(dbms, Version.of(483)))
        assertThrows(AssertionError::class.java) { assertIntrospectorRoutesToGeneric(dbms) }
    }

    fun testTheGateRoutesEveryVersionToGeneric() {
        registerDbmsExtension("introspector", dbms, Gate::class.java, testRootDisposable)
        assertIntrospectorRoutesToGeneric(dbms)
        assertTrue("the PostgreSQL dbms keeps its native introspector", supportedByNativeIntrospector(Dbms.POSTGRES, Version.of(17)))
    }
}
