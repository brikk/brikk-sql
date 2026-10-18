package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.introspection.supportedByNativeIntrospector
import com.intellij.database.util.Version
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertIntrospectorRoutesToGeneric

class GenericJdbcIntrospectorGateTest : BasePlatformTestCase() {
    class Gate : GenericJdbcIntrospectorGate()

    /** From 262, this overrides the interface's data-source `isSupported`, which the platform calls there. */
    class DataSourceAwareGate : GenericJdbcIntrospectorGate() {
        @Suppress("unused", "UNUSED_PARAMETER")
        fun isSupported(version: Version, point: DatabaseConnectionPoint, project: Project): Boolean = true
    }

    private val dbms = CatalogTestDbms.GATED

    override fun setUp() {
        super.setUp()
        registerDbms(dbms, testRootDisposable)
    }

    /** What the gate prevents: the PostgreSQL factory, inherited, accepts a Trino server's version. */
    fun testTheFallbackAloneIntrospectsNatively() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        assertTrue(supportedByNativeIntrospector(dbms, Version.of(483)))
        assertThrows(AssertionError::class.java) { assertIntrospectorRoutesToGeneric(dbms) }
    }

    fun testTheGateRoutesEveryVersionToGeneric() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("introspector", dbms, Gate::class.java, testRootDisposable)
        assertIntrospectorRoutesToGeneric(dbms)
        assertTrue("the PostgreSQL dbms keeps its native introspector", supportedByNativeIntrospector(Dbms.POSTGRES, Version.of(17)))
        val gate = Gate()
        assertTrue(gate.isNative())
        assertFalse(gate.isIncremental())
        assertFalse(gate.supportsMultilevelIntrospection)
        assertFalse(gate.supportsFragmentIntrospection)
    }

    /** A gate inherited from the fallback dbms routes today, but the registration the plugin relies on is missing. */
    fun testAnInheritedGateIsNotEnough() {
        val base = CatalogTestDbms.GATED_BASE
        registerDbms(base, testRootDisposable)
        registerFallback(dbms, base, testRootDisposable)
        registerDbmsExtension("introspector", base, Gate::class.java, testRootDisposable)
        assertInstanceOf(DbmsExtension<DBIntrospector.Factory>("com.intellij.database.introspector").forDbms(dbms), Gate::class.java)
        assertThrows(AssertionError::class.java) { assertIntrospectorRoutesToGeneric(dbms) }
        assertIntrospectorRoutesToGeneric(base)
    }

    fun testTheDataSourceOverloadIsChecked() {
        registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
        registerDbmsExtension("introspector", dbms, DataSourceAwareGate::class.java, testRootDisposable)
        val platformHasIt = DBIntrospector.Factory::class.java.methods.any { it.name == "isSupported" && it.parameterCount == 3 }
        if (platformHasIt) assertThrows(AssertionError::class.java) { assertIntrospectorRoutesToGeneric(dbms) } else assertIntrospectorRoutesToGeneric(dbms)
    }
}
