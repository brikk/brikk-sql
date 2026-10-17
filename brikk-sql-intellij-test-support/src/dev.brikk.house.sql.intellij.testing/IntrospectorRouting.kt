package dev.brikk.house.sql.intellij.testing

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.introspection.supportedByNativeIntrospector
import com.intellij.database.util.Version
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.brikk.house.sql.intellij.catalog.GenericJdbcIntrospectorGate

private const val INTROSPECTOR = "com.intellij.database.introspector"

private val introspectors = DbmsExtension<DBIntrospector.Factory>(INTROSPECTOR)

/** Below and at PostgreSQL's native threshold (9), common engine majors, and Trino's (483). */
private val serverMajors = listOf(0, 1, 8, 9, 10, 17, 25, 483, 10_000)

/**
 * Fails unless the platform introspects [dbms] through its generic JDBC introspector at every server
 * version: a [GenericJdbcIntrospectorGate] is registered for exactly this dbms, not inherited through
 * `extensionFallback`, the dbms resolves to it, and the platform's native-introspection check refuses
 * the dbms at each version, through the data-source overload too where the IDE has it (262 on).
 */
public fun assertIntrospectorRoutesToGeneric(dbms: Dbms) {
    val own = ApplicationManager.getApplication().extensionArea.getExtensionPoint<DbmsExtension.Bean<*>>(INTROSPECTOR)
        .extensionList.filter { it.key == dbms.name }
    if (own.isEmpty()) throw AssertionError("No introspector is registered for ${dbms.name} itself")
    val factory = introspectors.forDbms(dbms)
    if (factory !is GenericJdbcIntrospectorGate) {
        throw AssertionError("${dbms.name} resolves the introspector ${factory?.javaClass?.name}, not a GenericJdbcIntrospectorGate")
    }
    val withConnection = connectionOverload()
    val point = LocalDataSource()
    val project = ProjectManager.getInstance().defaultProject
    val native = serverMajors.filter { major ->
        val version = Version.of(major)
        supportedByNativeIntrospector(dbms, version) || withConnection?.invoke(null, dbms, version, point, project) == true
    }
    if (native.isNotEmpty()) throw AssertionError("${dbms.name} is introspected natively at server majors $native")
}

/** `supportedByNativeIntrospector(dbms, version, point, project)`, which production calls from 262 on. */
private fun connectionOverload() = runCatching {
    Class.forName("com.intellij.database.introspection.DBIntrospectorFactory", false, DBIntrospector.Factory::class.java.classLoader)
        .getMethod("supportedByNativeIntrospector", Dbms::class.java, Version::class.java, DatabaseConnectionPoint::class.java, Project::class.java)
}.getOrNull()
