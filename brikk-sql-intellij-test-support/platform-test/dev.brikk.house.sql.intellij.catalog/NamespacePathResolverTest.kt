package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.mysql.model.MysqlRoot
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.DasObject
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Real PostgreSQL (catalog, schema) and MySQL (schema) models, as the platform builds them. */
class NamespacePathResolverTest : BasePlatformTestCase() {
    private lateinit var catalogs: List<DasObject>
    private val twoLevels = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)

    override fun setUp() {
        super.setUp()
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.POSTGRES)
        val databases = (model.root as PgRoot).databases
        databases.createOrGet("hive").schemas.apply {
            createOrGet("web").tables.createOrGet("orders")
            createOrGet("app")
        }
        databases.createOrGet("memory")
        // A catalog whose name is also a schema of hive, and one differing from it only in case.
        databases.createOrGet("web")
        databases.createOrGet("Hive")
        catalogs = model.modelRoots.toList()
    }

    fun testAbsolutePathsResolveToTheirNode() {
        val resolver = NamespacePathResolver(twoLevels, usesCurrentNamespace = false)
        assertEquals(listOf("hive"), resolver.resolve(catalogs, listOf("hive"))?.path)
        val schema = resolver.resolve(catalogs, listOf("hive", "web"))
        assertEquals(listOf("hive", "web"), schema?.path)
        assertEquals(ObjectKind.SCHEMA, schema?.node?.kind)
        assertNull("a path that does not resolve is still being typed", resolver.resolve(catalogs, listOf("hive", "we")))
        assertNull("deeper than the namespaces", resolver.resolve(catalogs, listOf("hive", "web", "orders")))
    }

    fun testExactCaseWinsThenAnyCase() {
        val resolver = NamespacePathResolver(twoLevels, usesCurrentNamespace = false)
        assertEquals("Hive", resolver.resolve(catalogs, listOf("Hive"))?.node?.name)
        assertEquals("hive", resolver.resolve(catalogs, listOf("hive"))?.node?.name)
        assertEquals(listOf("hive", "web"), resolver.resolve(catalogs, listOf("HIVE", "WEB"))?.path?.map(String::lowercase))
    }

    fun testRelativePathsUseTheCurrentNamespace() {
        val resolver = NamespacePathResolver(twoLevels, usesCurrentNamespace = true)
        assertEquals(listOf("hive", "app"), resolver.resolve(catalogs, listOf("app"), listOf("hive", "web"))?.path)
        assertEquals("absolute wins", listOf("web"), resolver.resolve(catalogs, listOf("web"), listOf("hive"))?.path)
        assertNull(NamespacePathResolver(twoLevels, usesCurrentNamespace = false).resolve(catalogs, listOf("app"), listOf("hive")))
    }

    fun testObjectsBelowTheNamespacesUpToTheDepth() {
        val resolver = NamespacePathResolver(twoLevels, usesCurrentNamespace = true, maxDepth = 3)
        val table = resolver.resolve(catalogs, listOf("orders"), listOf("hive", "web"))
        assertEquals(listOf("hive", "web", "orders"), table?.path)
        assertEquals(ObjectKind.TABLE, table?.node?.kind)
    }

    fun testChildlessNamespaces() {
        val resolver = NamespacePathResolver(twoLevels, usesCurrentNamespace = false)
        assertFalse(resolver.isChildless(requireNotNull(resolver.resolve(catalogs, listOf("hive"))).node))
        assertTrue(resolver.isChildless(requireNotNull(resolver.resolve(catalogs, listOf("memory"))).node))
        assertTrue(resolver.isChildless(requireNotNull(resolver.resolve(catalogs, listOf("hive", "app"))).node))
    }

    fun testOneLevelModels() {
        val model = ModelFactory.BLACK_HOLE.createModel(Dbms.MYSQL)
        (model.root as MysqlRoot).schemas.createOrGet("shop")
        val resolver = NamespacePathResolver(listOf(ObjectKind.SCHEMA), usesCurrentNamespace = true)
        assertEquals(listOf("shop"), resolver.resolve(model.modelRoots.toList(), listOf("shop"), listOf("other"))?.path)
        assertNull(resolver.resolve(model.modelRoots.toList(), listOf("shop", "orders")))
    }
}
