package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.postgres.model.PgMetaModel
import com.intellij.database.dialects.postgres.model.PgModelHelper
import com.intellij.database.dialects.postgresgreenplumbase.model.PgGPlumBaseModelHelper
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class PgFamilyModelFacadeTest : BasePlatformTestCase() {
    class Facade(dbms: Dbms) : PgFamilyModelFacade(dbms)
    class CatalogFacade(dbms: Dbms) : PgFamilyModelFacade(dbms, databasesAsCatalogs = true)

    override fun setUp() {
        super.setUp()
        for ((dbms, facade) in listOf(CatalogTestDbms.FACADE to Facade::class.java, CatalogTestDbms.CATALOG_FACADE to CatalogFacade::class.java)) {
            registerDbms(dbms, testRootDisposable)
            registerFallback(dbms, Dbms.POSTGRES, testRootDisposable)
            registerDbmsExtension("modelFacade", dbms, facade, testRootDisposable)
        }
    }

    fun testThePlatformBuildsThePostgresModelForTheDbms() {
        val facade = ModelFacade.forDbms(CatalogTestDbms.FACADE)
        assertInstanceOf(facade, Facade::class.java)
        assertSame(PgMetaModel.MODEL, facade.metaModel)
        assertSame(PgModelHelper, facade.modelHelper)
    }

    fun testCatalogFacadeNamesDatabasesCatalogs() {
        val helper = ModelFacade.forDbms(CatalogTestDbms.CATALOG_FACADE).modelHelper
        assertEquals("catalog", helper.getName(ObjectKind.DATABASE, false))
        assertEquals("catalogs", helper.getName(ObjectKind.DATABASE, true))
        assertEquals(PgModelHelper.getName(ObjectKind.SCHEMA, false), helper.getName(ObjectKind.SCHEMA, false))
        assertFalse(PgModelHelper.getName(ObjectKind.DATABASE, false) == "catalog")
    }

    /** The catalog helper is the PostgreSQL helper apart from its name for databases. */
    fun testCatalogHelperKeepsEveryPostgresOverride() {
        val helper = ModelFacade.forDbms(CatalogTestDbms.CATALOG_FACADE).modelHelper
        val overrides = PgModelHelper.javaClass.declaredMethods.filter { it.overridesSuperclassMethod() }
        assertFalse(overrides.isEmpty())
        val missing = overrides.filter { method ->
            runCatching { helper.javaClass.getDeclaredMethod(method.name, *method.parameterTypes) }.isFailure
        }
        assertEmpty(missing.joinToString { it.toGenericString() }, missing)
        assertEquals(PgModelHelper.sequenceTypeSince, (helper as PgGPlumBaseModelHelper).sequenceTypeSince)
        assertEquals(PgModelHelper.getSyntheticColumnNameByPosition(3), helper.getSyntheticColumnNameByPosition(3))
    }

    private fun Method.overridesSuperclassMethod(): Boolean {
        if (isSynthetic || isBridge || Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers) || Modifier.isFinal(modifiers)) return false
        return generateSequence(declaringClass.superclass) { it.superclass }
            .flatMap { sequenceOf(it) + it.interfaces.asSequence() }
            .mapNotNull { type -> runCatching { type.getDeclaredMethod(name, *parameterTypes) }.getOrNull() }
            .any { !Modifier.isPrivate(it.modifiers) && !Modifier.isStatic(it.modifiers) }
    }
}
